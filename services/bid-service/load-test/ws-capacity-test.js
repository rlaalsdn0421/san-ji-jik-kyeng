/**
 * WebSocket 동시 접속 한계 테스트
 *
 * 목표: VU를 단계적으로 늘려 서버가 몇 명까지 안정적으로 수용하는지 측정
 *       → 연결 성공률, 응답시간, 에러 발생 지점(breaking point) 확인
 *
 * 실행:
 *   k6 run --env GATEWAY_URL=http://localhost:8000 \
 *           --env AUCTION_ID=<UUID> \
 *           ws-capacity-test.js
 *
 * 단계별 해석:
 *   - connected_rate 100% 유지 → 정상 수용
 *   - connected_rate 하락 시작 지점 = breaking point
 *   - stomp_connect_ms p95 급상승 지점 = 처리 한계
 */

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8000';
const AUCTION_ID  = __ENV.AUCTION_ID  || 'test-auction-id';
const WS_URL = GATEWAY_URL.replace('http', 'ws') + '/ws/bid-native';

// --- 커스텀 메트릭 ---
const connectedRate   = new Rate('ws_connected_rate');   // WebSocket 연결 성공률
const stompConnected  = new Rate('stomp_connected_rate'); // STOMP CONNECT 성공률
const connectErr      = new Counter('connect_error');
const stompConnectMs  = new Trend('stomp_connect_ms');    // STOMP 세션 수립 시간

export const options = {
    stages: [
        { duration: '20s', target: 3000 },  // 3000명
        { duration: '20s', target: 3000 },  // 유지
        { duration: '20s', target: 5000 },  // 5000명
        { duration: '30s', target: 5000 },  // 유지
        { duration: '20s', target: 7000 },  // 7000명
        { duration: '30s', target: 7000 },  // 유지
        { duration: '20s', target: 10000 }, // 10000명
        { duration: '30s', target: 10000 }, // 유지
        { duration: '20s', target: 0    },  // 종료
    ],
    thresholds: {
        'ws_connected_rate':    ['rate>0.95'],       // 95% 이상 연결 성공
        'stomp_connected_rate': ['rate>0.90'],       // 90% 이상 STOMP 세션 수립
        'stomp_connect_ms':     ['p(95)<3000'],      // STOMP 수립 p95 3초 이내
    },
};

export default function () {
    const userId = `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;

    const params = {
        headers: {
            'X-User-Id': userId,
            'X-User-Role': 'BUYER',
        },
    };

    const start = Date.now();

    const res = ws.connect(WS_URL, params, function (socket) {
        let stompOk = false;

        socket.on('open', () => {
            socket.send(
                `CONNECT\naccept-version:1.2\nX-User-Id:${userId}\nX-User-Role:BUYER\nheart-beat:0,0\n\n\x00`
            );
        });

        socket.on('message', (msg) => {
            if (msg.startsWith('CONNECTED') && !stompOk) {
                stompOk = true;
                stompConnectMs.add(Date.now() - start);
                stompConnected.add(1);

                // 경매 토픽 구독 (실 사용 시나리오 재현)
                socket.send(
                    `SUBSCRIBE\nid:sub-bid\ndestination:/topic/auction/${AUCTION_ID}\n\n\x00`
                );
            }
        });

        socket.on('error', () => {
            connectErr.add(1);
            if (!stompOk) stompConnected.add(0);
        });

        // 30초 접속 유지 후 종료 (실제 사용자가 경매방에 머무는 시간 시뮬레이션)
        socket.setTimeout(() => {
            if (!stompOk) stompConnected.add(0);
            socket.close();
        }, 30000);
    });

    const wsOk = res && res.status === 101;
    connectedRate.add(wsOk ? 1 : 0);
    if (!wsOk) connectErr.add(1);
}

export function handleSummary(data) {
    const get = (metric, field) =>
        data.metrics[metric]?.values?.[field] ?? 0;

    const connRate   = (get('ws_connected_rate', 'rate') * 100).toFixed(1);
    const stompRate  = (get('stomp_connected_rate', 'rate') * 100).toFixed(1);
    const p50        = get('stomp_connect_ms', 'p(50)').toFixed(0);
    const p95        = get('stomp_connect_ms', 'p(95)').toFixed(0);
    const p99        = get('stomp_connect_ms', 'p(99)').toFixed(0);
    const errCount   = get('connect_error', 'count');

    console.log('\n===== WebSocket 동시 접속 한계 테스트 결과 =====');
    console.log(`WebSocket 연결 성공률 : ${connRate}%`);
    console.log(`STOMP 세션 수립 성공률: ${stompRate}%`);
    console.log(`STOMP 수립 시간 p50   : ${p50}ms`);
    console.log(`STOMP 수립 시간 p95   : ${p95}ms`);
    console.log(`STOMP 수립 시간 p99   : ${p99}ms`);
    console.log(`연결 오류 수          : ${errCount}`);

    return {};
}
