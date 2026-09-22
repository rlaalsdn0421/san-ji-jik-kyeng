# order-service

산지직경 플랫폼의 주문(낙찰 후 거래) 서비스입니다. 낙찰자의 보증금(예치금) 처리와 낙찰 내역 조회를 담당하며, 경매·결제 서비스와 이벤트/API로 연동합니다.

## 주요 기능
- 낙찰 보증금(예치금) 등록 및 내 예치금 내역 조회
- 내 낙찰 내역 조회
- Kafka를 통한 경매/결제 이벤트 소비(낙찰, 결제 결과 등)와 주문 이벤트 발행 (Outbox 패턴 적용)
- Feign을 통한 auction-service, user-service 연동
- 시간 기반 자동 처리를 위한 스케줄러(`OrderScheduler`)

## 기술 스택
- Java 21, Spring Boot
- Spring Data JPA (Flyway 마이그레이션), PostgreSQL (H2는 테스트/로컬 런타임용)
- Spring Security
- Spring Kafka
- Spring Cloud OpenFeign
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| POST | /api/v1/orders/deposit | 보증금(예치금) 등록 |
| GET | /api/v1/orders/deposit/me | 내 예치금 내역 조회 |
| GET | /api/v1/orders/winning/me | 내 낙찰 내역 조회 |

내부 연동(Feign): `GET /internal/auctions/{auctionId}` (auction-service), `GET /internal/v1/users/{userId}/user-info` (user-service)

## 아키텍처
`presentation(controller/dto) → application(service/port) → domain(entity/repository/enums)` 구조이며, `infrastructure`에 Feign 클라이언트, Kafka 메시징(producer/consumer/handler/config), Outbox 구현이 위치합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19094`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:order-service:bootRun`
