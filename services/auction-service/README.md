# auction-service

산지직경 플랫폼의 경매(옥션) 및 상품 도메인을 담당하는 서비스입니다. 경매 생성·진행·마감과 농산물 상품 등록/관리를 처리하고, 입찰 서비스와 연동해 낙찰 결과를 반영합니다.

## 주요 기능
- 경매(Auction) 등록, 조회, 상태 변경(시작/취소/마감), 목록 조회
- 상품(Product) 등록, 조회, 수정, 삭제
- 경매 종료 시점 등 시간 기반 자동 처리를 위한 스케줄러(shedlock으로 분산 락 처리)
- Kafka를 통한 외부 이벤트 소비(입찰/낙찰 등)와 Outbox 패턴 기반 이벤트 발행
- bid-service의 최고 입찰가를 Feign 클라이언트로 조회

## 기술 스택
- Java 21, Spring Boot
- Spring Data JPA (Flyway 마이그레이션), PostgreSQL (H2는 테스트/로컬 런타임용)
- Spring Security
- Spring Kafka
- Spring Cloud OpenFeign (서비스 간 동기 호출)
- ShedLock (`shedlock-spring` + `shedlock-provider-jdbc-template`) — 스케줄러 분산 락
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| POST | /api/v1/auctions | 경매 등록 |
| GET | /api/v1/auctions/{auctionId} | 경매 상세 조회 |
| GET | /api/v1/auctions | 경매 목록 조회 |
| PATCH | /api/v1/auctions/{auctionId} | 경매 수정 |
| POST | /api/v1/auctions/{auctionId}/start | 경매 시작 |
| POST | /api/v1/auctions/{auctionId}/cancel | 경매 취소 |
| POST | /api/v1/auctions/{auctionId}/close | 경매 마감 |
| GET | /internal/auctions/{auctionId} | (내부용) 경매 조회 |
| POST | /api/v1/products | 상품 등록 |
| GET | /api/v1/products/{productId} | 상품 상세 조회 |
| GET | /api/v1/products | 상품 목록 조회 |
| PATCH | /api/v1/products/{productId} | 상품 수정 |
| DELETE | /api/v1/products/{productId} | 상품 삭제 |

## 아키텍처
`auction`, `product`, `outbox` 세 하위 도메인으로 나뉘며 각각 `presentation → application(service/port/dto) → domain(entity/repository/type)` 계층을 가집니다. `infrastructure`에 Feign 클라이언트, Kafka 메시징(consumer), 스케줄러, 트랜잭션 처리가 위치하고, `global`에 공통 설정/예외/유틸이 있습니다. Outbox 패턴(`outbox` 도메인 + `AuctionOutboxRelay`)으로 이벤트 발행 신뢰성을 확보합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19092` (로컬 프로필 `application-local.yml` 기준)
- 로컬 실행: 저장소 루트에서 `./gradlew :services:auction-service:bootRun`
