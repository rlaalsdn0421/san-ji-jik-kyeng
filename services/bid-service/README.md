# bid-service

산지직경 플랫폼의 실시간 입찰(경매 응찰) 서비스입니다. WebSocket(STOMP)을 통해 실시간으로 입찰을 받고, Redis/Redisson으로 최고가를 관리하며, 안티스나이핑(막판 저격 입찰 방지) 로직을 포함합니다.

## 주요 기능
- 실시간 입찰 처리 및 최고 입찰가 조회
- WebSocket(STOMP) 기반 실시간 입찰 브로드캐스트, 접속 시 정지 사용자 차단
- Redis(Redisson)를 이용한 동시성 제어 및 최고가 캐싱
- 경매 종료 스케줄링(`AuctionEndScheduler`) 및 Kafka를 통한 입찰/경매 이벤트 발행·소비

## 기술 스택
- Java 21, Spring Boot
- Spring WebSocket (STOMP, SockJS)
- Spring Data Redis + Redisson (`redisson-spring-boot-starter`)
- Spring Kafka
- Spring Security (+ spring-security-test)
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| GET | /api/v1/bids/auctions/{auctionId}/highest | 특정 경매의 최고 입찰가 조회 |
| WS | /ws/bid (SockJS), /ws/bid-native | 실시간 입찰 STOMP 엔드포인트 (`/app` prefix로 발행, `/topic`,`/queue` 구독) |

## 아키텍처
`presentation(controller/dto) → application/service → domain(model/event/exception)` 구조이며, `infrastructure`에 WebSocket 설정, Redis 설정, Kafka 프로듀서/컨슈머, 스케줄러가 위치합니다. STOMP CONNECT 시 `X-User-Id` 헤더 검증과 Redis 기반 정지 사용자 확인을 인터셉터에서 수행합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19093`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:bid-service:bootRun`
