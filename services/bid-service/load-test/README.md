# Bid Service 성능 테스트

## 설치

```bash
# macOS
brew install k6

# Windows (winget)
winget install k6

# Docker
docker pull grafana/k6
```

---

## 사전 준비

테스트 전에 Redis에 경매 데이터를 직접 세팅해야 합니다.

```bash
# Redis에 경매 정보 세팅 (auction-start Kafka 이벤트 대신 직접 주입)
redis-cli HSET auction:{AUCTION_ID}:info \
  productName "테스트 상품" \
  currentPrice 10000 \
  bidUnit 1000 \
  startAt "2025-01-01T00:00:00" \
  endAt "2025-12-31T23:59:59" \
  status "PROGRESS" \
  highestBidderId ""

# 입찰자 보증금 납부 처리 (deposit 키 등록)
# 각 VU userId에 대해 설정
redis-cli SET auction:{AUCTION_ID}:deposit:{USER_ID} "true" EX 86400
```

> **주의**: `clientSeenPrice`는 Redis의 `currentPrice`와 일치해야 합니다.
> 테스트 스크립트의 `CURRENT_PRICE` 환경변수를 Redis 값과 맞춰주세요.

---

## 1. 동시 입찰 테스트 (concurrent-bid-test.js)

**목적**: 50명이 동시 입찰 시 Redisson 분산 락이 정확히 동작하는지 검증

```bash
k6 run \
  --env GATEWAY_URL=http://localhost:8000 \
  --env AUCTION_ID=<경매 UUID> \
  --env CURRENT_PRICE=10000 \
  concurrent-bid-test.js
```

**핵심 확인 지표**:
| 지표 | 정상 | 버그 |
|------|------|------|
| `bid_success` | ≤ 1 (동시 가격 기준) | ≥ 2 → 분산 락 버그 |
| `bid_failed` | 대부분 BID_PRICE_OUTDATED | - |
| `bid_latency_ms p(95)` | < 2,000ms | 초과 시 락 경합 과다 |

---

## 2. 동시 접속 테스트 (concurrent-connection-test.js)

**목적**: 300명이 동시 구독 시 Redis Pub/Sub → WebSocket 브로드캐스트가 모두에게 도달하는지 검증

```bash
k6 run \
  --env GATEWAY_URL=http://localhost:8000 \
  --env AUCTION_ID=<경매 UUID> \
  --env BIDDER_USER_ID=<입찰할 유저 UUID> \
  concurrent-connection-test.js
```

**핵심 확인 지표**:
| 지표 | 정상 기준 |
|------|----------|
| `connect_success_rate` | > 99% |
| `broadcast_latency_ms p(95)` | < 500ms |
| `broadcast_missed` | 0 |

---

## 결과 해석

### 동시 입찰에서 `bid_success >= 2`가 나오면
→ Redisson 락이 제대로 동작하지 않는 것. `tryLock(3, 5, SECONDS)` 설정 확인

### 브로드캐스트 미수신이 발생하면
→ `RedisMessageListenerContainer` 연결 문제 또는 STOMP 메시지 큐 포화
→ 커넥션 수 늘리거나 SimpleBroker → RabbitMQ 브로커 전환 검토

### 접속 수 증가에 따라 응답 지연이 급증하면
→ SimpleBroker(인메모리) 한계. 외부 메시지 브로커(RabbitMQ, Redis STOMP) 도입 필요
