# payment-service

산지직경 플랫폼의 결제 서비스입니다. 낙찰 건에 대한 결제 승인/재결제를 외부 PG 연동으로 처리하고, 결제 상태를 관리하며 관련 이벤트를 발행/소비합니다.

## 주요 기능
- 결제 승인(confirm), 결제 재시도(repay), 결제 단건/주문별 조회
- 외부 결제 대행사(PG) API 연동 (`infrastructure/external`)
- Kafka를 통한 주문/경매 이벤트 소비(낙찰 생성, 경매 실패 등)와 결제 이벤트 발행 (Outbox 패턴 적용)
- 결제 상태 관리를 위한 스케줄러(`PaymentScheduler`)
- Redis를 활용한 캐싱/상태 관리

## 기술 스택
- Java 21, Spring Boot
- Spring Data JPA (Flyway 마이그레이션), PostgreSQL
- Spring Data Redis
- Spring Security
- Spring Kafka
- Spring Cloud OpenFeign
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| POST | /api/v1/payments/confirm | 결제 승인 |
| GET | /api/v1/payments/{paymentId} | 결제 단건 조회 |
| POST | /api/v1/payments/repay/{orderId} | 결제 재시도(재결제) |
| GET | /api/v1/payments/order/{orderId} | 주문 기준 결제 조회 |

## 아키텍처
`presentation(controller/dto) → application(service/port) → domain(entity/repository/enums)` (도메인 패키지명은 `domian`으로 표기됨) 구조이며, `infrastructure`에 외부 PG 연동, Kafka 메시징(producer/consumer/handler), Outbox 구현이 위치합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19095`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:payment-service:bootRun`
