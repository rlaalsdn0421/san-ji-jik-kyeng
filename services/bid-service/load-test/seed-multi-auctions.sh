#!/bin/bash
# 다중 경매 시딩 스크립트 (2단계 처리량 테스트용)
# Kafka/auction-service 경유 없이 Redis에 경매 N건 + 경매당 입찰 보증금(deposit)을 직접 시딩한다.
#
# 사용: ./seed-multi-auctions.sh <경매수> <경매당비더수> [출력JSON경로]
set -e

NUM_AUCTIONS=${1:-20}
BIDDERS_PER_AUCTION=${2:-10}
OUT_JSON=${3:-"$(dirname "$0")/auctions.json"}
REDIS_CONTAINER=${REDIS_CONTAINER:-sanji-dev-redis-1}
CURRENT_PRICE=10000
BID_UNIT=1000
SELLER_ID="00000000-0000-0000-0000-999999999999"

# 컨테이너(Asia/Seoul)와 시간대를 맞춰야 함 — LocalDateTime.now()가 endAt보다 미래면 AUCTION_ENDED로 즉시 거부됨
START_AT=$(date +"%Y-%m-%dT%H:%M:%S")
END_AT=$(date -d "+1 hour" +"%Y-%m-%dT%H:%M:%S")

CMDS=$(mktemp)
echo "[" > "$OUT_JSON"

for i in $(seq 0 $((NUM_AUCTIONS - 1))); do
  AUCTION_ID=$(printf "a0000000-0000-4000-8000-%012d" "$i")
  HASH_KEY="auction:${AUCTION_ID}:info"

  {
    echo "HSET $HASH_KEY productName load-test-product-$i sellerId $SELLER_ID currentPrice $CURRENT_PRICE bidUnit $BID_UNIT startAt $START_AT endAt $END_AT status PROGRESS highestBidderId \"\""
    echo "EXPIRE $HASH_KEY 3600"
  } >> "$CMDS"

  for b in $(seq 1 "$BIDDERS_PER_AUCTION"); do
    VU=$((i * BIDDERS_PER_AUCTION + b))
    USER_ID=$(printf "00000000-0000-0000-0000-%012d" "$VU")
    echo "SET auction:${AUCTION_ID}:deposit:${USER_ID} true EX 3600" >> "$CMDS"
  done

  if [ "$i" -gt 0 ]; then echo "," >> "$OUT_JSON"; fi
  printf '"%s"' "$AUCTION_ID" >> "$OUT_JSON"
done

echo "]" >> "$OUT_JSON"

CMD_COUNT=$(wc -l < "$CMDS")
echo "Redis 명령 ${CMD_COUNT}건 실행 중..."
docker exec -i "$REDIS_CONTAINER" redis-cli < "$CMDS" > /dev/null
rm -f "$CMDS"

TOTAL_VUS=$((NUM_AUCTIONS * BIDDERS_PER_AUCTION))
echo "완료: 경매 ${NUM_AUCTIONS}건 x 경매당 비더 ${BIDDERS_PER_AUCTION}명 = 총 VU ${TOTAL_VUS} -> ${OUT_JSON}"
