/**
 * 동시 입찰 테스트
 *
 * 목표: 같은 경매에 N명이 동시에 입찰 → Redisson 락 + clientSeenPrice 검증
 * 기대 결과:
 *   - 동일 가격 기준으로 락 점유 성공한 1명만 SUCCESS
 *   - 나머지는 BID_PRICE_OUTDATED (정상 동작)
 *   - SUCCESS가 2개 이상이면 버그
 *
 * 실행: k6 run --env GATEWAY_URL=http://localhost:8000 \
 *              --env AUCTION_ID=<UUID> \
 *              --env CURRENT_PRICE=10000 \
 *              concurrent-bid-test.js
 */

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY_URL  = __ENV.GATEWAY_URL  || 'http://localhost:8000';
const AUCTION_ID   = __ENV.AUCTION_ID   || 'test-auction-id';
const CURRENT_PRICE = parseInt(__ENV.CURRENT_PRICE || '10000');
const WS_URL = GATEWAY_URL.replace('http', 'ws') + '/ws/bid-native';

// --- 커스텀 메트릭 ---
const bidSuccess   = new Counter('bid_success');    // 입찰 성공 수
const bidFailed    = new Counter('bid_failed');     // 입찰 실패 수 (BID_PRICE_OUTDATED 등)
const bidDuplicate = new Counter('bid_duplicate');  // 동시 성공 → 버그 감지
const connectErr   = new Counter('connect_error');
const bidLatency   = new Trend('bid_latency_ms');

// 성공 카운트 공유 (k6는 VU 간 공유 안 되므로 메트릭으로 판별)
const VUS = parseInt(__ENV.VUS || '50');

export const options = {
    scenarios: {
        concurrent_bids: {
            executor: 'shared-iterations',  // 전체 N번을 VU들이 나눠서 처리
            vus: VUS,                       // 동시 접속 유저 수 (VUS 환경변수로 조절)
            iterations: VUS,                // 총 입찰 시도 수 (vus == iterations → 한 번에 모두 동시)
            maxDuration: '60s',
        },
    },
    thresholds: {
        // 성공은 반드시 1건 이하여야 함 (동시 낙찰 방지 검증)
        // 실제 집계는 k6 결과 요약에서 bid_success 확인
        'bid_latency_ms': ['p(95)<2000'],  // 95% 요청이 2초 이내
    },
};

export default function () {
    const userId = `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;
    // VU마다 다른 입찰가 (currentPrice + VU번호 * 1000 → 락 해제 후 순서대로 성공 가능하도록)
    const bidPrice = CURRENT_PRICE + (__VU * 1000);

    const connectStart = Date.now();

    const params = {
        headers: {
            'X-User-Id': userId,
            'X-User-Role': 'BUYER',
        },
    };

    const res = ws.connect(WS_URL, params, function (socket) {
        socket.on('open', () => {
            // STOMP CONNECT
            socket.send(
                `CONNECT\naccept-version:1.2\nX-User-Id:${userId}\nX-User-Role:BUYER\nheart-beat:0,0\n\n\x00`
            );
        });

        socket.on('message', (msg) => {
            // CONNECTED 수신 → 구독 + 입찰 전송
            if (msg.startsWith('CONNECTED')) {
                // 에러 수신용 구독
                socket.send(
                    `SUBSCRIBE\nid:sub-err\ndestination:/user/queue/errors\n\n\x00`
                );
                // 입찰 이벤트 구독
                socket.send(
                    `SUBSCRIBE\nid:sub-bid\ndestination:/topic/auction/${AUCTION_ID}\n\n\x00`
                );

                // 입찰 전송
                const payload = JSON.stringify({
                    bidPrice: bidPrice,
                    clientSeenPrice: CURRENT_PRICE,  // 모두 동일한 currentPrice로 전송 → 락 경합 발생
                });
                const sendStart = Date.now();
                socket.send(
                    `SEND\ndestination:/app/auction/${AUCTION_ID}/bid\ncontent-type:application/json\n\n${payload}\x00`
                );

                // 결과 대기 (최대 3초)
                socket.setTimeout(() => {
                    socket.close();
                }, 3000);
            }

            // 입찰 성공 (BID_UPDATED 브로드캐스트 수신)
            if (msg.includes('"type":"BID_UPDATED"') && msg.includes(`"highestBidderId":"${userId}"`)) {
                bidLatency.add(Date.now() - connectStart);
                bidSuccess.add(1);
                socket.close();
            }

            // 입찰 실패 (에러 메시지 수신)
            if (msg.includes('BID_FAILED')) {
                bidLatency.add(Date.now() - connectStart);
                bidFailed.add(1);
                socket.close();
            }
        });

        socket.on('error', (e) => {
            connectErr.add(1);
        });
    });

    check(res, { 'ws connected': (r) => r && r.status === 101 });
}

export function handleSummary(data) {
    const success = data.metrics['bid_success'] ? data.metrics['bid_success'].values.count : 0;

    console.log('\n===== 동시 입찰 테스트 결과 =====');
    console.log(`총 입찰 시도: ${data.metrics['iterations']?.values?.count ?? 0}`);
    console.log(`입찰 성공 수: ${success}`);
    console.log(`입찰 실패 수: ${data.metrics['bid_failed'] ? data.metrics['bid_failed'].values.count : 0}`);
    console.log(`연결 오류: ${data.metrics['connect_error'] ? data.metrics['connect_error'].values.count : 0}`);

    if (success > 1) {
        console.log(`\n⚠️  경고: 동시 낙찰 ${success}건 발생 → 분산 락 버그!`);
    } else {
        console.log(`\n✅ 정상: 동시 성공 1건 이하`);
    }

    const lat = data.metrics['bid_latency_ms']?.values;
    const durationMs = data.state?.testRunDurationMs ?? 0;
    const totalIters = data.metrics['iterations']?.values?.count ?? 0;

    console.log('\n===== 지표 =====');
    if (lat) {
        console.log(`bid_latency_ms: avg=${lat.avg.toFixed(1)} min=${lat.min.toFixed(1)} p(90)=${lat['p(90)'].toFixed(1)} p(95)=${lat['p(95)'].toFixed(1)} max=${lat.max.toFixed(1)}`);
    }
    if (durationMs > 0) {
        console.log(`처리시간: ${(durationMs / 1000).toFixed(2)}s, TPS(iterations/s): ${(totalIters / (durationMs / 1000)).toFixed(1)}`);
    }

    return {};
}
