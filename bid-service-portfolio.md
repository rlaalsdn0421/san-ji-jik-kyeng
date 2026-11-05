## ▶ 입찰 서비스 (bid service)

- 실시간 입찰(WebSocket/STOMP), 최고 입찰 조회 REST API

- 동시에 여러 사용자가 입찰할 경우 같은 currentPrice를 기반으로 중복 낙찰·가격 경합이 발생하는 구조
Redisson 분산 락(`auction:{id}:lock`)을 적용해 입찰 단위로 직렬화. 락 획득 실패 시 즉시 반환해 응답 지연을 최소화하는 try-lock 방식으로 구현

- 마감 직전 입찰이 집중돼 실질적 참여 시간이 불균등해지는 문제
입찰 성공 시 종료 시각을 현재 기준 +1분으로 갱신하는 anti-sniping 로직 구현. endAt을 Redis Hash에 덮어쓰고 ZSet(`auction:endings`)의 score도 동시에 갱신해 스케줄러가 새 종료 시각을 인식하도록 설계

- DB 폴링 없이 경매 종료 시점을 정확히 감지해야 하고, 다중 인스턴스에서 AUCTION_ENDED가 중복 발행되면 낙찰 이벤트가 두 번 처리되는 위험
Redis ZSet을 종료 시각(epoch) 기준 정렬 인덱스로 사용하고 5초 폴링 스케줄러가 만료 항목을 수거. 종료 처리 전 Redisson 락(`auction:{id}:end-lock`)을 선점해 단일 인스턴스만 Kafka AUCTION_ENDED를 발행하도록 보장

- 입찰 성공 결과를 같은 경매방의 모든 접속자에게 즉시 전파해야 하는 구조
BidService가 Redis Pub/Sub 채널(`auction:{id}:bid-event`)에 발행하고, RedisBidSubscriber가 수신해 STOMP `/topic/auction/{id}`로 브로드캐스트하는 2단계 구조로 설계. 입찰 처리와 브로드캐스트를 분리해 WebSocket 의존성 없이 서비스 레이어를 유지

- STOMP CONNECT 시점에 정지 유저가 입찰방에 진입하는 것을 차단해야 하는 구조
ChannelInterceptor로 CONNECT 프레임을 인터셉트해 Redis Set(`suspended:users`) 조회 후 차단. 동시에 X-User-Id/X-User-Role을 읽어 UserPrincipal을 세션에 주입

- auction-start Kafka 이벤트가 재전송될 경우 Redis에 같은 경매 데이터가 중복 적재되는 위험
Hash 키 존재 여부를 선조회해 이미 처리된 경매는 즉시 스킵하는 멱등성 처리 구현

- k6로 50 VU 동시 입찰 부하 테스트를 수행해 bid_success: 1 / bid_failed: 49 결과 확인. 분산 락이 단 1건만 낙찰 처리하고 나머지를 정상 거부하는 동작 검증
