/**
 * 다중 경매 처리량 한계 테스트 (2단계)
 *
 * 1단계(concurrent-bid-test.js)는 "경매 1건"에 락 경합을 몰아 락 자체의 한계를 봤다.
 * 이건 락 설계상 구조적 한계라 인스턴스를 늘려도 해결되지 않는다.
 *
 * 2단계는 반대로 "서로 다른 경매 N건"에 경매당 소수 비더(락 경합 낮게 유지)만 배치해,
 * 경매 수(=총 VU)를 늘려가며 시스템 전체가 실제로 처리 가능한 총 TPS 한계를 본다.
 * 여기서 처리량이 무너지면 원인은 락이 아니라 WebSocket/Tomcat 스레드풀, DB 커넥션풀,
 * Redis 처리량 등 인스턴스 자체의 자원 한계로 봐야 한다.
 *
 * 사전 준비: ./seed-multi-auctions.sh <경매수> <경매당비더수> 로 auctions.json 생성
 *
 * 실행: k6 run --env GATEWAY_URL=http://localhost:8000 \
 *              --env BIDDERS_PER_AUCTION=10 \
 *              --env AUCTIONS_FILE=./auctions.json \
 *              multi-auction-throughput-test.js
 */

import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8000';
const CURRENT_PRICE = parseInt(__ENV.CURRENT_PRICE || '10000');
const BIDDERS_PER_AUCTION = parseInt(__ENV.BIDDERS_PER_AUCTION || '10');
const AUCTIONS_FILE = __ENV.AUCTIONS_FILE || './auctions.json';
const WS_URL = GATEWAY_URL.replace('http', 'ws') + '/ws/bid-native';

// seed-multi-auctions.sh가 생성한 경매 ID 목록. 배열 인덱스가 곧 시딩 시 사용한 경매 순서.
const AUCTION_IDS = JSON.parse(open(AUCTIONS_FILE));
const VUS = AUCTION_IDS.length * BIDDERS_PER_AUCTION;

const bidSuccess = new Counter('bid_success');
const bidFailed  = new Counter('bid_failed');
const connectErr = new Counter('connect_error');
const bidLatency = new Trend('bid_latency_ms');

export const options = {
    scenarios: {
        multi_auction_bids: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: VUS,
            maxDuration: '60s',
        },
    },
    thresholds: {
        'bid_latency_ms': ['p(95)<2000'],
    },
};

export default function () {
    // seed-multi-auctions.sh와 동일한 규칙: VU (i*BIDDERS_PER_AUCTION + b) -> 경매 i, 비더 b
    const auctionIndex = Math.floor((__VU - 1) / BIDDERS_PER_AUCTION);
    const auctionId = AUCTION_IDS[auctionIndex];
    const bidderSeq = ((__VU - 1) % BIDDERS_PER_AUCTION) + 1;

    const userId = `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;
    const bidPrice = CURRENT_PRICE + (bidderSeq * 1000);

    const connectStart = Date.now();

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
                socket.send(`SUBSCRIBE\nid:sub-err\ndestination:/user/queue/errors\n\n\x00`);
                socket.send(`SUBSCRIBE\nid:sub-bid\ndestination:/topic/auction/${auctionId}\n\n\x00`);

                const payload = JSON.stringify({
                    bidPrice: bidPrice,
                    clientSeenPrice: CURRENT_PRICE,
                });
                socket.send(
                    `SEND\ndestination:/app/auction/${auctionId}/bid\ncontent-type:application/json\n\n${payload}\x00`
                );

                socket.setTimeout(() => {
                    socket.close();
                }, 3000);
            }

            if (msg.includes('"type":"BID_UPDATED"') && msg.includes(`"highestBidderId":"${userId}"`)) {
                bidLatency.add(Date.now() - connectStart);
                bidSuccess.add(1);
                socket.close();
            }

            if (msg.includes('BID_FAILED')) {
                bidLatency.add(Date.now() - connectStart);
                bidFailed.add(1);
                socket.close();
            }
        });

        socket.on('error', () => {
            connectErr.add(1);
        });
    });

    check(res, { 'ws connected': (r) => r && r.status === 101 });
}

export function handleSummary(data) {
    const success = data.metrics['bid_success'] ? data.metrics['bid_success'].values.count : 0;
    const failed  = data.metrics['bid_failed'] ? data.metrics['bid_failed'].values.count : 0;
    const connErr = data.metrics['connect_error'] ? data.metrics['connect_error'].values.count : 0;
    const totalIters = data.metrics['iterations']?.values?.count ?? 0;
    const durationMs = data.state?.testRunDurationMs ?? 0;
    const timeout = totalIters - success - failed - connErr;

    console.log('\n===== 다중 경매 처리량 테스트 결과 =====');
    console.log(`경매 수: ${AUCTION_IDS.length}, 경매당 비더: ${BIDDERS_PER_AUCTION}, 총 VU: ${VUS}`);
    console.log(`총 시도: ${totalIters}, 성공: ${success}, 실패(정상거부): ${failed}, 미응답(3s): ${timeout}, 연결오류: ${connErr}`);

    const expectedSuccessMax = AUCTION_IDS.length; // 경매당 최대 1건 성공이어야 정상
    if (success > expectedSuccessMax) {
        console.log(`\n⚠️  경고: 경매당 1건을 초과하는 성공 발생 → 락 버그 의심 (성공 ${success} > 경매수 ${expectedSuccessMax})`);
    } else {
        console.log(`\n✅ 정상: 경매당 성공 1건 이하 유지`);
    }

    const lat = data.metrics['bid_latency_ms']?.values;
    console.log('\n===== 지표 =====');
    if (lat) {
        console.log(`bid_latency_ms: avg=${lat.avg.toFixed(1)} min=${lat.min.toFixed(1)} p(90)=${lat['p(90)'].toFixed(1)} p(95)=${lat['p(95)'].toFixed(1)} max=${lat.max.toFixed(1)}`);
    }
    if (durationMs > 0) {
        console.log(`처리시간: ${(durationMs / 1000).toFixed(2)}s, TPS(iterations/s): ${(totalIters / (durationMs / 1000)).toFixed(1)}`);
    }

    return {};
}
