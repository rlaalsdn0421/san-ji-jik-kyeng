# notification-service

산지직경 플랫폼의 알림 서비스입니다. 경매/주문/결제 등 다른 서비스에서 발생한 이벤트를 Kafka로 수신해 사용자 알림(및 Slack 알림)을 생성·발송하고, 알림 이력을 조회할 수 있게 합니다.

## 주요 기능
- 내 알림 목록/단건 조회, 관리자용 전체 알림 조회
- Kafka 이벤트 소비 기반 알림 생성 (`NotificationEventConsumer`)
- Slack을 통한 알림 발송 (`infrastructure/slack/SlackNotificationSender`)
- 알림 발송 실패 시 재시도 스케줄러 (`NotificationRetryScheduler`)
- Redis 캐시를 통한 사용자 알림 수신 동의 여부 캐싱 (`UserNotificationCacheService`)
- Feign을 통한 user-service 연동으로 알림 수신 가능 여부 확인
- Resilience4j 서킷브레이커 적용

## 기술 스택
- Java 21, Spring Boot
- Spring Data JPA (Flyway 마이그레이션), PostgreSQL
- Spring Data Redis
- Spring Security
- Spring Kafka
- Spring Cloud OpenFeign, Spring Cloud Circuit Breaker (Resilience4j)
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| GET | /api/v1/notifications | 내 알림 목록 조회 |
| GET | /api/v1/notifications/{notificationId} | 알림 단건 조회 |
| GET | /api/v1/admin/notifications | (관리자) 전체 알림 조회 |

내부 연동(Feign): `GET /internal/v1/users/{userId}/notify-allow` (user-service)

## 아키텍처
`presentation(controller/dto) → application(service/event/port) → domain(entity/repository/enums)` 구조이며, `infrastructure`에 Kafka 메시징, Redis 캐시 설정, Feign 클라이언트, Slack 연동, 재시도 스케줄러가 위치합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19096`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:notification-service:bootRun`
