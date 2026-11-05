# Bid Service 도메인 문서

> **서비스 포트:** 19093
>
> **기술 스택:** Spring Boot 3.3 · WebSocket (STOMP) · Apache Kafka · Redis (Lettuce + Redisson)

---

## 1. 서비스 설계 배경 — 왜 이렇게 설계했는가

### 이 서비스가 무엇인가

산지직경 경매 플랫폼에서 **실시간 입찰을 처리하는 서비스**다.
auction-service가 경매를 시작하면 Kafka 이벤트를 수신해 Redis에 경매 상태를 저장하고, 클라이언트는 WebSocket(STOMP)으로 연결해 입찰을 요청하며, 입찰 결과는 실시간으로 전체 참여자에게 브로드캐스트된다.
경매가 종료되면 스케줄러가 자동으로 낙찰 결과를 Kafka로 발행한다.

### 왜 DB 없이 Redis만 사용하는가

경매 입찰 데이터는 경매가 종료된 뒤에는 bid-service에 남을 필요가 없다. 낙찰자와 최종가는 `auction-ended` Kafka 이벤트로 auction-service에 전달되어 영구 저장된다. bid-service가 관리하는 현재가, 최고 입찰자 등의 정보는 경매 진행 중에만 필요한 **휘발성 데이터**다.

Redis Hash에 경매별 상태를 저장하고 TTL을 경매 종료 시각으로 설정하면, 경매가 끝난 뒤 데이터가 자동 만료되므로 별도 정리 로직이 필요 없다. DB를 도입하면 읽기/쓰기 레이턴시가 늘어나 ms 단위 입찰 처리에 불리하고, 경매 종료 후 데이터 삭제 배치 작업도 추가로 필요해진다.

### 왜 Redis Pub/Sub + WebSocket 조합인가

입찰이 발생하면 같은 경매에 연결된 모든 클라이언트에게 동일한 데이터를 즉시 전달해야 한다. BidService가 입찰 처리 후 Redis Pub/Sub 채널(`auction:{id}:bid-event`)에 발행하고, `RedisBidSubscriber`가 이를 수신해 WebSocket `/topic/auction/{id}`로 브로드캐스트한다.

이 구조의 핵심 이점은 **수평 확장**이다. bid-service 인스턴스가 여러 개 떠 있어도 Redis Pub/Sub이 모든 인스턴스에 메시지를 전달하므로, 어떤 인스턴스에 연결된 클라이언트든 동일한 브로드캐스트를 받는다. `SimpMessagingTemplate`으로 직접 브로드캐스트하면 다중 인스턴스 환경에서 동일 인스턴스에 연결된 클라이언트에게만 전달된다.

### 왜 경매 종료를 ZSet + 스케줄러로 처리하는가

Redis TTL 만료 이벤트(Keyspace Notification)를 사용하는 방안을 검토했으나, TTL 만료 알림은 보장된 순서와 정확한 타이밍을 제공하지 않고 Redis 서버 설정(`notify-keyspace-events`)을 변경해야 하는 부담이 있다.

`auction:endings` ZSet에 auctionId를 endAt epoch 스코어로 저장하고, 10초 주기 스케줄러가 현재 시각 이하의 스코어를 `rangeByScore`로 조회하는 방식이 더 단순하고 예측 가능하다. 다중 인스턴스 환경에서는 Redisson 분산 락(`auction:{id}:end-lock`)으로 중복 처리를 방지한다.

---

## 2. 기술 선택 — 후보와 선택 이유

### 2-1. 분산 락 — Redisson

동일 경매에 동시에 여러 입찰이 들어오면 `currentPrice`를 읽고 검증하는 사이에 레이스 컨디션이 발생한다.

| 방식 | 동작 | 판단 |
| --- | --- | --- |
| DB 비관적 락 | 조회 시 행 잠금 | bid-service는 DB 미사용 |
| Redis WATCH/MULTI/EXEC | 낙관적 트랜잭션, 충돌 시 재시도 | 클라이언트 재시도 구현 복잡 |
| **Redisson 분산 락 (선택)** | tryLock(3초 대기, 5초 보유), 분산 환경 상호 배제 보장 | 단순한 API, 다중 인스턴스 안전 |

Redisson의 `tryLock(3, 5, SECONDS)`로 최대 3초 대기 후 락을 획득하지 못하면 예외를 던진다. 락 보유 시간 5초 내에 처리가 완료되지 않으면 자동 해제되어 데드락을 방지한다.

### 2-2. clientSeenPrice — 낙관적 동시성 검증

입찰 요청 시 클라이언트가 화면에서 보고 있던 가격(`clientSeenPrice`)을 함께 전송한다. 서버는 Redis의 `currentPrice`와 비교해 일치하지 않으면 `BID_PRICE_OUTDATED(BID-004)` 에러를 반환한다.

분산 락이 직렬화를 보장하지만, 락 획득 후 현재가가 이미 다른 입찰자에 의해 바뀐 상황을 `clientSeenPrice` 비교로 추가 검증한다. 클라이언트는 에러를 받으면 최신 가격을 새로고침해 다시 입찰할 수 있다.

### 2-3. Kafka 컨슈머 에러 처리 — DLQ 없이 FixedBackOff

| 방식 | 동작 | 판단 |
| --- | --- | --- |
| DLQ (Dead Letter Queue) | 실패 메시지를 별도 토픽에 적재해 나중에 재처리 | DLQ 토픽 관리, 재처리 로직 추가 필요. 6인 팀 오버엔지니어링. |
| **FixedBackOff + 스킵 (선택)** | 1초 간격 3회 재시도 후 실패 메시지 로그 후 스킵 | 컨슈머 스레드 유지, 구현 단순, 로그로 실패 추적 가능 |

`auction-start` 이벤트 처리 실패 시 해당 경매 입찰이 불가능해지는 심각한 문제가 발생한다. 따라서 컨슈머 자체에서도 `try-catch`로 방어해 메시지 파싱 실패가 Kafka 재시도로 번지지 않도록 격리한다.

### 2-4. STOMP + SockJS

순수 WebSocket(`ws://`)은 일부 프록시, 방화벽 환경에서 차단된다. SockJS는 WebSocket을 먼저 시도하고 실패 시 HTTP 롱폴링 등으로 자동 폴백해 브라우저 호환성을 높인다.

두 엔드포인트를 모두 등록했다. `/ws/bid`(SockJS)는 프론트엔드 브라우저용이고, `/ws/bid-native`는 SockJS 없이 순수 WebSocket으로 직접 연결할 클라이언트(테스트 도구, 모바일 앱 등)를 위한 것이다.

### 2-5. UserPrincipal — WebSocket 인증

Spring Security JWT 필터를 WebSocket에 적용하면 STOMP 프레임마다 토큰 검증이 발생하고 설정이 복잡해진다. bid-service는 이미 gateway-server에서 인증된 `X-User-Id` 헤더를 신뢰하는 구조다.

CONNECT 인터셉터에서 `X-User-Id` 헤더를 읽어 `UserPrincipal(userId)`을 세션에 등록한다. 이후 `BidController`에서 `Principal` 파라미터로 userId를 꺼내고, `/user/queue/errors` 개인 구독은 Spring이 `Principal.name` 기반으로 해당 세션에만 라우팅한다.

---

## 3. 사용하지 않은 것 (Not Used) 및 이유

| 항목 | 고려했던 이유 | 미채택 이유 |
| --- | --- | --- |
| **DB (JPA/PostgreSQL)** | 영속성, 쿼리 유연성 | 입찰 데이터는 경매 종료 후 필요 없는 휘발성 데이터. Redis TTL로 자동 만료. |
| **Redis Keyspace Notification** | TTL 만료 시 자동 이벤트 트리거 | 타이밍 보장 없음. Redis 서버 설정 변경 필요. ZSet + 스케줄러가 더 단순하고 예측 가능. |
| **SimpMessagingTemplate 직접 브로드캐스트** | Redis Pub/Sub 없이 단순 구현 | 단일 인스턴스에서만 동작. 수평 확장 시 동일 인스턴스 클라이언트에게만 전달됨. |
| **Spring Security WebSocket 필터** | JWT 표준 인증 | 게이트웨이에서 인증 완료 후 X-User-Id 전달 구조. 중복 인증 불필요. CONNECT 인터셉터로 충분. |
| **DLQ (Dead Letter Queue)** | 실패 메시지 추후 재처리 가능 | 6인 팀 규모에서 오버엔지니어링. FixedBackOff 3회 재시도 + 로그 스킵으로 충분. |
| **redisson-spring-boot-starter** | Spring Boot 자동 설정 편의 | RedissonConnectionFactory가 LettuceConnectionFactory를 대체해 pExpire 무한 재귀 StackOverflow 발생. redisson 단독 jar 사용. |

---

## 4. Redis 데이터 구조

### `auction:{auctionId}:info` (Hash)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `productName` | String | 상품명 (스냅샷) |
| `currentPrice` | String (int) | 현재 최고 입찰가 |
| `bidUnit` | String (int) | 최소 입찰 단위 |
| `startAt` | String (LocalDateTime) | 경매 시작 시각 |
| `endAt` | String (LocalDateTime) | 경매 종료 시각 (안티스나이핑 시 갱신됨) |
| `status` | String | 경매 상태 (PROGRESS 등) |
| `highestBidderId` | String (UUID) | 현재 최고 입찰자 ID (없으면 빈 문자열) |

**TTL:** 경매 종료 시각까지 설정 (Lua 스크립트로 EXPIRE 호출 — TS-1 참고)

### `auction:endings` (ZSet)

auctionId를 member로, endAt의 **KST epoch second**를 score로 저장.
`AuctionEndScheduler`가 `rangeByScore(0, now)`로 종료된 경매를 일괄 조회한다.

### `auction:{auctionId}:deposit:{userId}` (Key)

payment-service가 보증금 결제 확인 후 등록하는 키. bid-service는 이 키의 존재 여부로 입찰 자격을 검증한다. TTL은 endAt + 2시간 (payment-service 설정).

### `suspended:users` (Set)

정지된 사용자의 userId 목록. CONNECT 인터셉터에서 `isMember` 조회해 정지 유저의 WebSocket 연결을 차단한다.

### `auction:{auctionId}:lock` (Redisson Lock)

입찰 처리 중 동시성 제어용 분산 락. `tryLock(3초 대기, 5초 보유)`.

### `auction:{auctionId}:end-lock` (Redisson Lock)

`AuctionEndScheduler`의 종료 처리 중복 방지용 분산 락. 다중 인스턴스 환경에서 동일 경매를 여러 인스턴스가 동시에 처리하는 것을 막는다.

---

## 5. API

### WebSocket (STOMP)

| 구분 | 주소 | 설명 |
| --- | --- | --- |
| 연결 (SockJS) | `ws://localhost:8000/ws/bid` | SockJS 폴백 지원. 프론트엔드 기본 연결 주소. |
| 연결 (Native) | `ws://localhost:8000/ws/bid-native` | 순수 WebSocket. SockJS 미사용 클라이언트용. |
| CONNECT 헤더 | `X-User-Id: {userId}` | 필수. 없으면 연결 거부. 정지 유저도 거부. |
| 입찰 발행 | `/app/auction/{auctionId}/bid` | `BidRequestDto { bidPrice, clientSeenPrice }` 전송 |
| 전체 구독 | `/topic/auction/{auctionId}` | 입찰 결과, 경매 종료 브로드캐스트 수신 |
| 개인 구독 | `/user/queue/errors` | 입찰 실패 시 개인 에러 메시지 수신 |

### REST API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/bids/auctions/{auctionId}/highest` | 현재 최고 입찰가 및 최고 입찰자 조회 (폴백용) |

### 브로드캐스트 메시지 포맷

입찰 성공 시 `/topic/auction/{auctionId}`로 전송:

```json
{
  "type": "BID_UPDATED",
  "auctionId": "...",
  "currentPrice": 10000,
  "previousHighestBidderId": "...",
  "highestBidderId": "...",
  "nextMinPrice": 11000
}
```

경매 종료 시:

```json
{ "type": "AUCTION_ENDED" }
```

입찰 실패 시 `/user/queue/errors`로 전송:

```json
{ "type": "BID_FAILED", "code": "BID-xxx", "message": "..." }
```

### 에러 코드

| 코드 | HTTP | 메시지 |
| --- | --- | --- |
| BID-001 | 404 | 존재하지 않는 경매입니다. |
| BID-002 | 400 | 진행 중인 경매가 아닙니다. |
| BID-003 | 400 | 종료된 경매입니다. |
| BID-004 | 409 | 현재 입찰가가 변경되었습니다. 새로고침 후 다시 시도해주세요. |
| BID-005 | 400 | 이미 최고 입찰자입니다. |
| BID-006 | 400 | 보증금이 납부되지 않았습니다. |
| BID-007 | 400 | 입찰가가 현재가보다 낮거나 같습니다. |

---

## 6. Kafka 이벤트

### 구독 토픽

| 토픽 | 처리 내용 |
| --- | --- |
| `auction-start` | 경매 시작 이벤트 수신 → Redis Hash + ZSet 저장, TTL 설정 |

### 발행 토픽

| 토픽 | 이벤트 | 발행 조건 | 주요 필드 |
| --- | --- | --- | --- |
| `bid-overtaken` | BidOvertakenEvent | 입찰 성공 시 (이전 최고 입찰자 알림용) | auctionId, auctionTitle, previousBidderId, newPrice, nextMinPrice, occurredAt |
| `auction-extended` | AuctionExtendedEvent | 안티스나이핑 발동 (종료 30초 이내 입찰) | auctionId, newEndAt |
| `auction-ended` | AuctionEndedEvent | 경매 종료 스케줄러가 처리 완료 시 | auctionId, hasBid, winnerId, finalPrice, endedAt |

---

## 7. 입찰 처리 전체 흐름

### 경매 시작

1. auction-service가 `auction-start` 토픽에 `AuctionStartedEvent` 발행
2. `AuctionEventConsumer` 수신 → `endAt = startAt + 1시간` 계산
3. Redis Hash(`auction:{id}:info`) 저장 + Lua 스크립트로 EXPIRE 설정
4. ZSet(`auction:endings`)에 `auctionId : endAt epoch` 스코어로 추가

### 입찰

1. 클라이언트 STOMP 연결 (`X-User-Id` 헤더 필수) → `UserPrincipal` 등록
2. `/app/auction/{id}/bid` 메시지 전송 → `BidController` → `BidService`
3. Redisson 분산 락 획득 → Redis에서 경매 정보 조회 → 유효성 검사 7단계
    - 경매 존재 여부 (BID-001)
    - 상태 PROGRESS 여부 (BID-002)
    - 종료 시각 초과 여부 (BID-003)
    - clientSeenPrice == currentPrice 여부 (BID-004)
    - bidPrice > currentPrice 여부 (BID-007)
    - 본인이 이미 최고 입찰자인지 (BID-005)
    - 보증금 납부 여부 (BID-006)
4. **(안티스나이핑)** 종료 30초 이내면 endAt 1분 연장, Hash/ZSet 갱신, `auction-extended` 발행
5. `currentPrice`, `highestBidderId` 갱신 → Redis Pub/Sub 발행 → `bid-overtaken` Kafka 발행
6. `RedisBidSubscriber` 수신 → `/topic/auction/{id}` WebSocket 브로드캐스트

### 경매 종료

1. `AuctionEndScheduler` 10초 주기 실행 → ZSet `rangeByScore(0, now)` 조회
2. auctionId별 Redisson 분산 락 획득 → 중복 처리 방지
3. `/topic/auction/{id}`에 `AUCTION_ENDED` 브로드캐스트
4. Redis Hash에서 `highestBidderId`, `finalPrice` 조회 → `auction-ended` Kafka 발행 (3초 동기 대기)
5. ZSet에서 auctionId 제거 (발행 성공 후)

---

## 8. 트러블슈팅

### TS-1. pExpire StackOverflowError — Redisson + Lettuce 충돌

|  |  |
| --- | --- |
| **상황** | `redisTemplate.expire()` 호출 시 StackOverflowError 발생. 경매 정보가 Redis에 저장되지만 TTL이 설정되지 않고, ZSet 추가 라인도 실행되지 않아 경매 종료 스케줄러가 동작하지 않는 복합 장애 발생. |
| **문제** | `redisson-spring-boot-starter`가 `LettuceConnectionFactory`를 `RedissonConnectionFactory`로 교체한다. `RedissonConnection.keyCommands()`가 `this`를 반환하므로 `DefaultedRedisConnection.pExpire()` 호출 시 `keyCommands().pExpire()` → `this.pExpire()` → 무한 재귀 → StackOverflow. |
| **시도한 방법들** | `expireAt()`, `expire(Duration)`, `RMap.expire()`, `@Primary LettuceConnectionFactory` 빈 등록, `redisson` → `redisson-spring-boot-starter` 교체 등 모두 동일 경로를 통과해 실패. |
| **행동** | Lua 스크립트로 `keyCommands()` 경로를 우회. `DefaultRedisScript`로 `redis.call('EXPIRE', KEYS[1], ARGV[1])`를 서버 사이드에서 직접 실행. `scriptingCommands().eval()`은 `keyCommands()`를 거치지 않아 재귀가 발생하지 않음. |
| **결과** | TTL 정상 설정. ZSet 추가 라인도 정상 실행되어 경매 종료 스케줄러 동작 복구. |

### TS-2. ZSet auction:endings 미적재 — 예외가 실행 흐름을 끊음

|  |  |
| --- | --- |
| **상황** | Redis에 경매 Hash는 저장되는데 `auction:endings` ZSet에는 auctionId가 추가되지 않아 `AuctionEndScheduler`가 경매 종료를 감지하지 못함. |
| **문제** | 코드상 `redisTemplate.expire()` 다음 줄에 ZSet 추가가 있는데, `expire()` 호출 시 StackOverflowError가 발생해 이후 코드가 실행되지 않았음. `catch (Exception e)`는 Error를 잡지 못해 ZSet 추가 실패가 로그에도 남지 않음. |
| **행동** | TS-1(Lua 스크립트)을 먼저 해결해 예외 발생 자체를 제거. |
| **결과** | ZSet 정상 적재. 경매 종료 스케줄러 동작 확인. |

### TS-3. ZSet 스코어 시간대 오류 — KST를 UTC로 잘못 계산

|  |  |
| --- | --- |
| **상황** | ZSet에 auctionId가 적재되어 있으나 `AuctionEndScheduler`가 `rangeByScore`로 조회해도 경매가 종료되지 않음. Redis에서 직접 스코어를 확인하니 예상보다 32,400(9시간 × 3,600초) 큰 값이 저장되어 있음. |
| **문제** | `endAt.toEpochSecond(ZoneOffset.UTC)`는 LocalDateTime을 UTC 기준 epoch으로 변환한다. 그러나 endAt은 KST 시각이므로 실제보다 9시간 후의 epoch이 저장됨. 스케줄러는 시스템 시각(UTC) 기준 `Instant.now().getEpochSecond()`와 비교하므로 9시간이 지나야 조회됨. |
| **행동** | `endAt.atZone(ZoneId.of("Asia/Seoul")).toEpochSecond()`로 변경. KST LocalDateTime을 올바른 epoch으로 변환. |
| **결과** | 스코어 정상화. 경매 종료 시각에 스케줄러가 정상 감지. |

### TS-4. Redisson DNS 리졸버 — Docker 내부 UnknownHostException

|  |  |
| --- | --- |
| **상황** | Docker Compose 환경에서 bid-service 기동 시 RedissonClient 초기화 실패. UnknownHostException 발생. |
| **문제** | Redisson이 내부적으로 Netty DNS 리졸버를 사용하는데, Docker 내부 DNS(127.0.0.11)와 Netty 리졸버 간 호환 문제로 redis 호스트명을 resolve하지 못함. |
| **행동** | `RedissonClient` 빈을 직접 등록해 `config.setAddressResolverGroupFactory()`로 `DefaultAddressResolverGroup.INSTANCE`(JVM 기본 DNS)를 강제 지정. |
| **결과** | Docker 내부 DNS로 redis 호스트명 정상 resolve. 기동 성공. |

### TS-5. Kafka __consumer_offsets 생성 실패 — 단일 브로커 설정 누락

|  |  |
| --- | --- |
| **상황** | bid-service Kafka 컨슈머가 `auction-start` 토픽 메시지를 수신하지 못함. 로그에 `__consumer_offsets` 토픽 생성 실패 오류 발생. |
| **문제** | Kafka 기본 `replication.factor`는 3이나 Docker Compose 단일 브로커 환경에서 브로커 수가 1개라 ISR을 충족하지 못해 내부 토픽 생성 실패. 컨슈머 오프셋 커밋 불가 → 메시지 수신 불가. |
| **행동** | `docker-compose.yml` Kafka 환경 변수에 `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1` 추가. |
| **결과** | 내부 토픽 정상 생성. 컨슈머 `auction-start` 토픽 정상 수신. |
