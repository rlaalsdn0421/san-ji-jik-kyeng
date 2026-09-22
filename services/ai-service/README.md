# ai-service

산지직경 플랫폼의 AI 어시스턴트 서비스입니다. RAG(검색 증강 생성) 기반 챗봇으로 사용자 문의에 응답하고, 관리자가 업로드한 문서를 벡터 스토어에 색인하여 답변 근거로 활용합니다.

## 주요 기능
- 사용자와의 채팅 세션 생성, 대화(질의응답), 세션/메시지 조회 및 삭제
- 관리자용 지식 문서 업로드/조회/수정/삭제 (PDF, Word, Excel 등 다양한 포맷 지원)
- 업로드된 문서를 텍스트 추출 후 청크 분할하여 벡터 DB(pgvector)에 임베딩 저장
- 질의 시 유사도 기반 문서 검색(top-k, similarity-threshold) 후 LLM 응답 생성
- Langfuse를 통한 LLM 호출 트레이싱

## 기술 스택
- Java 21, Spring Boot
- Spring Web (REST API), Spring Security
- Spring Data JPA + PostgreSQL (Flyway 마이그레이션, 스키마 `ai_schema`)
- Spring AI (`spring-ai-starter-model-openai` — OpenAI 호환 API로 Gemini 연동, `spring-ai-starter-vector-store-pgvector`)
- Apache Tika (문서 텍스트 추출: PDF/Word/Excel 등)
- Micrometer Tracing (OTel) + Langfuse 연동
- springdoc-openapi (Swagger UI)

## API
| Method | Path | 설명 |
|---|---|---|
| POST | /api/v1/ai/sessions | 채팅 세션 생성 |
| POST | /api/v1/ai/sessions/{sessionId}/chat | 채팅 메시지 전송 및 응답 생성 |
| GET | /api/v1/ai/sessions | 세션 목록 조회 |
| GET | /api/v1/ai/sessions/{sessionId}/messages | 세션 내 메시지 조회 |
| DELETE | /api/v1/ai/sessions/{sessionId} | 세션 삭제 |
| POST | /api/v1/admin/ai/documents | (관리자) 문서 업로드 (multipart) |
| GET | /api/v1/admin/ai/documents | (관리자) 문서 목록 조회 |
| PUT | /api/v1/admin/ai/documents/{source} | (관리자) 문서 갱신 (multipart) |
| DELETE | /api/v1/admin/ai/documents/{source} | (관리자) 문서 삭제 |

## 아키텍처
`presentation(controller/dto)` → `application/service` → `domain(entity/repository)` 계층 구조이며, `infrastructure`에 AI 연동(`infrastructure/ai`), Langfuse 트레이싱(`infrastructure/langfuse`), 영속성 구현(`infrastructure/persistence`)이 분리되어 있습니다.

## 실행
- Dockerfile: `eclipse-temurin:21-jre-alpine` 베이스, `app.jar` 실행 (포트는 `application.yml`의 `server.port` 기준)
- 기본 포트: `19097`
- 로컬 실행: 저장소 루트에서 `./gradlew :services:ai-service:bootRun`
- `GEMINI_API_KEY` 등 환경 변수 필요 (OpenAI 호환 엔드포인트로 Gemini 모델 사용)
