# user-service

산지직경 플랫폼의 사용자/인증 서비스입니다. 회원가입, 로그인, 회원 정보 관리와 Keycloak 기반 인증/인가를 담당하며, 다른 서비스에서 사용자 정보를 조회할 수 있는 내부 API를 제공합니다.

## 주요 기능
- 회원가입(일반/관리자), 로그인, 토큰 재발급(refresh)
- 내 정보 조회/수정(프로필, 사업자 정보), 회원 탈퇴
- 회원 단건/전체 조회, 회원 정지/정지 해제(관리자)
- Keycloak Admin Client를 통한 사용자 계정 연동
- 정지된 사용자 목록을 Redis에 캐싱 (`SuspendedUserCacheInitializer`) — 다른 서비스(bid-service 등)에서 실시간 차단에 활용
- Resilience4j 기반 서킷브레이커/타임아웃 적용

## 기술 스택
- Java 21, Spring Boot
- Spring Data JPA (Flyway 마이그레이션), PostgreSQL
- Spring Data Redis
- Spring Security
- Keycloak Admin Client (`keycloak-admin-client`)
- Resilience4j (`resilience4j-spring-boot3`) + Spring AOP
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| POST | /api/v1/auth/signup | 회원가입 |
| POST | /api/v1/auth/admin/signup | 관리자 회원가입 |
| POST | /api/v1/auth/login | 로그인 |
| POST | /api/v1/auth/refresh | 토큰 재발급 |
| GET | /api/v1/users/one | 회원 단건 조회 |
| GET | /api/v1/users/all | 회원 전체 조회 |
| GET | /api/v1/users/me | 내 정보 조회 |
| PATCH | /api/v1/users/me/profile | 내 프로필 수정 |
| PATCH | /api/v1/users/me/business | 내 사업자 정보 수정 |
| DELETE | /api/v1/users/me | 회원 탈퇴 |
| PATCH | /api/v1/users/suspended | 회원 정지 |
| PATCH | /api/v1/users/unsuspended | 회원 정지 해제 |
| GET | /internal/v1/users/{userId}/notify-allow | (내부용) 알림 수신 가능 여부 조회 |
| GET | /internal/v1/users/{userId}/user-info | (내부용) 사용자 정보 조회 |

## 아키텍처
`presentation(controller/dto) → application/service → domain(entity/repository/exception)` 구조이며, `infrastructure`에 Keycloak 연동(`infrastructure/keycloak`)과 공통 설정(`infrastructure/config`)이 위치합니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행
- 기본 포트: `19091`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:user-service:bootRun`
