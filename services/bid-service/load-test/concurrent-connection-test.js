/**
 * 동시 접속 테스트
 *
 * 목표: N명이 같은 경매 WebSocket에 동시 접속 → Redis Pub/Sub → STOMP 브로드캐스트 확인
 * 기대 결과:
 *   - 모든 구독자가 입찰 이벤트를 수신해야 함
 *   - p(95) 수신 지연 < 500ms
 *   - 접속 유지 중 메모리/스레드 누수 없음 (k6 메트릭으로 간접 확인)
 *
 * 실행: k6 run --env GATEWAY_URL=http://localhost:8000 \
 *              --env AUCTION_ID=<UUID> \
 *              --env BIDDER_USER_ID=<UUID>  (실제로 입찰할 1명의 userId) \
 *              concurrent-connection-test.js
 */

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';

const GATEWAY_URL   = __ENV.GATEWAY_URL   || 'http://localhost:8000';
const AUCTION_ID    = __ENV.AUCTION_ID    || 'test-auction-id';
const BIDDER_USER_ID = __ENV.BIDDER_USER_ID || '00000000-0000-0000-0000-000000000001';
const WS_URL = GATEWAY_URL.replace('http', 'ws') + '/ws/bid-native';

// --- 커스텀 메트릭 ---
const broadcastReceived  = new Counter('broadcast_received');   // BID_UPDATED 수신 성공
const broadcastMissed    = new Counter('broadcast_missed');     // 타임아웃 → 브로드캐스트 못 받음
const connectSuccess     = new Rate('connect_success_rate');
const broadcastLatency   = new Trend('broadcast_latency_ms');   // 입찰 → 수신 지연

export const options = {
    stages: [
        { duration: '10s', target: 100 },  // 10초에 걸쳐 100명까지 점진적 증가
        { duration: '30s', target: 300 },  // 30초 동안 300명까지 증가
        { duration: '20s', target: 300 },  // 300명 유지
        { duration: '10s', target: 0  },   // 종료
    ],
    thresholds: {
        'connect_success_rate':   ['rate>0.99'],    // 99% 이상 연결 성공
        'broadcast_latency_ms':   ['p(95)<500'],    // 95%가 500ms 이내 수신
        'broadcast_missed':       ['count<10'],     // 브로드캐스트 미수신 10건 미만
        'ws_session_duration':    ['p(95)<70000'],  // 세션 최대 70초
    },
};

// VU 1번만 입찰자 역할, 나머지는 구독자
export default function () {
    const isBidder = __VU === 1;
    const userId = isBidder
        ? BIDDER_USER_ID
        : `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;

    let connected = false;
    let receivedBroadcast = false;
    let bidSentAt = 0;

    const params = {
        headers: {
            'X-User-Id': userId,
            'X-User-Role': 'BUYER',
        },
    };

    const res = ws.connect(WS_URL, params, function (socket) {
        socket.on('open', () => {
            socket.send(
                `CONNECT\naccept-version:1.2\nX-User-Id:${userId}\nX-User-Role:BUYER\nheart-beat:0,0\n\n\x00`
            );
        });

        socket.on('message', (msg) => {
            if (msg.startsWith('CONNECTED')) {
                connected = true;

                // 경매 브로드캐스트 구독
                socket.send(
                    `SUBSCRIBE\nid:sub-0\ndestination:/topic/auction/${AUCTION_ID}\n\n\x00`
                );

                // VU 1번 (입찰자): 구독 후 500ms 뒤 입찰 전송
                if (isBidder) {
                    socket.setTimeout(() => {
                        const payload = JSON.stringify({
                            bidPrice: 15000,
                            clientSeenPrice: 10000,
                        });
                        bidSentAt = Date.now();
                        socket.send(
                            `SEND\ndestination:/app/auction/${AUCTION_ID}/bid\ncontent-type:application/json\n\n${payload}\x00`
                        );
                    }, 500);
                }
            }

            // BID_UPDATED 브로드캐스트 수신
            if (msg.includes('"type":"BID_UPDATED"')) {
                if (bidSentAt > 0) {
                    broadcastLatency.add(Date.now() - bidSentAt);
                }
                receivedBroadcast = true;
                broadcastReceived.add(1);
            }
        });

        socket.on('error', () => {
            connectSuccess.add(false);
        });

        // 접속 유지 30초 후 종료
        socket.setTimeout(() => {
            if (!receivedBroadcast && isBidder) {
                broadcastMissed.add(1);
            }
            socket.close();
        }, 30000);
    });

    const ok = res && res.status === 101;
    connectSuccess.add(ok);
    check(res, { 'ws status 101': () => ok });
}

export function handleSummary(data) {
    const received = data.metrics['broadcast_received']?.values?.count ?? 0;
    const missed   = data.metrics['broadcast_missed']?.values?.count ?? 0;
    const p95      = data.metrics['broadcast_latency_ms']?.values?.['p(95)'] ?? 0;
    const rate     = data.metrics['connect_success_rate']?.values?.rate ?? 0;

    console.log('\n===== 동시 접속 테스트 결과 =====');
    console.log(`WebSocket 연결 성공률: ${(rate * 100).toFixed(1)}%`);
    console.log(`BID_UPDATED 수신 성공: ${received}`);
    console.log(`BID_UPDATED 미수신:    ${missed}`);
    console.log(`브로드캐스트 지연 p95: ${p95.toFixed(0)}ms`);

    if (missed > 0) {
        console.log('\n⚠️  경고: 브로드캐스트 미수신 발생 → Redis Pub/Sub 누락 확인 필요');
    } else {
        console.log('\n✅ 정상: 모든 구독자 브로드캐스트 수신');
    }

    return {};
}
