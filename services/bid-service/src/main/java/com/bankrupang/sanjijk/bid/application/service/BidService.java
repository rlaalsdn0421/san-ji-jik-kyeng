package com.bankrupang.sanjijk.bid.application.service;

import com.bankrupang.sanjijk.bid.domain.event.BidOvertakenEvent;
import com.bankrupang.sanjijk.bid.domain.exception.BidErrorCode;
import com.bankrupang.sanjijk.bid.domain.exception.BidException;
import com.bankrupang.sanjijk.bid.infrastructure.kafka.BidEventProducer;
import com.bankrupang.sanjijk.bid.presentation.dto.BidBroadcastDto;
import com.bankrupang.sanjijk.bid.presentation.dto.BidRequestDto;
import com.bankrupang.sanjijk.bid.presentation.dto.HighestBidResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.script.DefaultRedisScript;

@Slf4j
@Service
@RequiredArgsConstructor
public class BidService {

    // AuctionEndScheduler는 5초 간격 폴링이라, Redis TTL이 종료 판정 시각과 정확히 같으면
    // 스케줄러가 읽기 전에 해시가 먼저 만료돼 유찰로 오판될 수 있어 버퍼를 둔다.
    private static final long END_CHECK_TTL_BUFFER_SECONDS = 60;

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BidEventProducer bidEventProducer;

    // 입찰 검사·갱신·브로드캐스트 발행을 Redis 안에서 한 번에 처리한다(락 불필요).
    // shortcut: endAt은 LocalDateTime.toString 형식만 파싱, 그 외 형식이면 AUCTION_ENDED로 거부. 다른 형식을 쓰게 되면 파서 교체
    private static final String BID_LUA = """
            local h = redis.call('HMGET', KEYS[1], 'sellerId', 'status', 'endAt', 'currentPrice', 'highestBidderId', 'bidUnit', 'productName')
            local seller, status, endAt, cur, prev, unit, product = h[1], h[2], h[3], h[4], h[5], h[6], h[7]
            if not cur then return {'AUCTION_NOT_FOUND'} end
            if seller == ARGV[1] then return {'SELLER_BID'} end
            if status ~= 'PROGRESS' then return {'AUCTION_NOT_IN_PROGRESS'} end
            local function ts(s)
              local y, mo, d, hh, mi, ss, fr = string.match(s, '^(%d+)-(%d+)-(%d+)T(%d+):(%d+):?(%d*)%.?(%d*)')
              if not y then return nil end
              local v = ((((tonumber(y) * 100 + tonumber(mo)) * 100 + tonumber(d)) * 100 + tonumber(hh)) * 100 + tonumber(mi)) * 100 + (tonumber(ss) or 0)
              if fr ~= '' then v = v + tonumber('0.' .. fr) end
              return v
            end
            local e, n = ts(endAt or ''), ts(ARGV[2])
            if not e or not n or n > e then return {'AUCTION_ENDED'} end
            cur = tonumber(cur)
            if tonumber(ARGV[3]) ~= cur then return {'BID_PRICE_OUTDATED'} end
            local price = tonumber(ARGV[4])
            if price <= cur then return {'BID_PRICE_TOO_LOW'} end
            prev = prev or ''
            if prev == ARGV[1] then return {'ALREADY_HIGHEST_BIDDER'} end
            if redis.call('EXISTS', KEYS[2]) == 0 then return {'DEPOSIT_NOT_PAID'} end
            redis.call('HSET', KEYS[1], 'endAt', ARGV[5], 'currentPrice', ARGV[4], 'highestBidderId', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            redis.call('ZADD', KEYS[3], ARGV[7], ARGV[8])
            local nextMin = price + tonumber(unit)
            redis.call('PUBLISH', KEYS[4], string.format(
              '{"type":"BID_UPDATED","auctionId":"%s","currentPrice":%d,"previousHighestBidderId":"%s","highestBidderId":"%s","nextMinPrice":%d}',
              ARGV[8], price, prev, ARGV[1], nextMin))
            return {'OK', prev, product or '', tostring(nextMin)}
            """;

    private static final DefaultRedisScript<List> BID_SCRIPT = new DefaultRedisScript<>(BID_LUA, List.class);

    @Value("${bid.lua-enabled:true}")
    private boolean luaEnabled;

    public void bid(UUID auctionId, UUID userId, BidRequestDto request) {
        if (luaEnabled) {
            bidWithLua(auctionId, userId, request);
        } else {
            bidWithLock(auctionId, userId, request);
        }
    }

    private void bidWithLua(UUID auctionId, UUID userId, BidRequestDto request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndAt = now.plusMinutes(1);
        long newTtl = Duration.ofMinutes(1).getSeconds() + END_CHECK_TTL_BUFFER_SECONDS;
        String id = auctionId.toString();
        List<?> r = redisTemplate.execute(BID_SCRIPT,
                List.of("auction:" + id + ":info", "auction:" + id + ":deposit:" + userId,
                        "auction:endings", "auction:" + id + ":bid-event"),
                userId.toString(), now.toString(), String.valueOf(request.getClientSeenPrice()),
                String.valueOf(request.getBidPrice()), newEndAt.toString(), String.valueOf(newTtl),
                String.valueOf(newEndAt.atZone(ZoneId.of("Asia/Seoul")).toEpochSecond()), id);

        String result = (String) r.get(0);
        if (!"OK".equals(result)) {
            throw new BidException(BidErrorCode.valueOf(result));
        }
        bidEventProducer.sendBidOvertaken(new BidOvertakenEvent(
                id, (String) r.get(2), (String) r.get(1), request.getBidPrice(),
                Integer.parseInt((String) r.get(3)), LocalDateTime.now()));
    }

    private void bidWithLock(UUID auctionId, UUID userId, BidRequestDto request) {
        String lockKey = "auction:" + auctionId + ":lock";
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RuntimeException("입찰 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }

            log.info("분산 락 획득 - auctionId: {}, userId: {}", auctionId, userId);

            String hashKey = "auction:" + auctionId + ":info";
            Map<Object, Object> info = redisTemplate.opsForHash().entries(hashKey);
            if (userId.equals(info.get("sellerId"))) {
                throw new BidException(BidErrorCode.SELLER_BID);
            }
            if (info.isEmpty()) {
                throw new BidException(BidErrorCode.AUCTION_NOT_FOUND);
            }

            String status = (String) info.get("status");

            if (!"PROGRESS".equals(status)) {
                throw new BidException(BidErrorCode.AUCTION_NOT_IN_PROGRESS);
            }

            LocalDateTime endAt = LocalDateTime.parse((String) info.get("endAt"));

            if (LocalDateTime.now().isAfter(endAt)) {
                throw new BidException(BidErrorCode.AUCTION_ENDED);
            }

            Integer currentPrice = Integer.parseInt((String) info.get("currentPrice"));

            if (!request.getClientSeenPrice().equals(currentPrice)) {
                throw new BidException(BidErrorCode.BID_PRICE_OUTDATED);
            }


            if (request.getBidPrice() <= currentPrice) {
                throw new BidException(BidErrorCode.BID_PRICE_TOO_LOW);
            }

            String highestBidderId = (String) info.get("highestBidderId");

            if (userId.toString().equals(highestBidderId)) {
                throw new BidException(BidErrorCode.ALREADY_HIGHEST_BIDDER);
            }


            Boolean hasPaid = redisTemplate.hasKey("auction:" + auctionId + ":deposit:" + userId);
            if (!Boolean.TRUE.equals(hasPaid)) {
                throw new BidException(BidErrorCode.DEPOSIT_NOT_PAID);
            }


            LocalDateTime newEndAt = LocalDateTime.now().plusMinutes(1);
            long newTtl = Duration.ofMinutes(1).getSeconds() + END_CHECK_TTL_BUFFER_SECONDS;
            redisTemplate.opsForHash().put(hashKey, "endAt", newEndAt.toString());
            DefaultRedisScript<Long> expireScript = new DefaultRedisScript<>(
                    "return redis.call('EXPIRE', KEYS[1], ARGV[1])", Long.class);
            redisTemplate.execute(expireScript, Collections.singletonList(hashKey), String.valueOf(newTtl));
            redisTemplate.opsForZSet().add("auction:endings", auctionId.toString(), newEndAt.atZone(ZoneId.of("Asia/Seoul")).toEpochSecond());
            log.info("입찰 처리 - auctionId: {}, newEndAt: {}", auctionId, newEndAt);

            redisTemplate.opsForHash().put(hashKey, "currentPrice", String.valueOf(request.getBidPrice()));
            redisTemplate.opsForHash().put(hashKey, "highestBidderId", userId.toString());
            log.info("현재가 갱신 - auctionId: {}, newPrice: {}, highestBidder: {}", auctionId, request.getBidPrice(), userId);

            Integer bidUnit = Integer.parseInt((String) info.get("bidUnit"));
            BidBroadcastDto broadcast = new BidBroadcastDto(
                    "BID_UPDATED",
                    auctionId.toString(),
                    request.getBidPrice(),
                    highestBidderId,
                    userId.toString(),
                    request.getBidPrice() + bidUnit
            );
            try {
                String payload = objectMapper.writeValueAsString(broadcast);
                redisTemplate.convertAndSend("auction:" + auctionId + ":bid-event", payload);
                log.info("브로드캐스트 발행 - channel: auction:{}:bid-event", auctionId);
            } catch (JsonProcessingException e) {
                log.error("브로드캐스트 직렬화 실패 - auctionId: {}", auctionId, e);
            }

            String productName = (String) info.get("productName");
            String previousBidderId = (highestBidderId == null || highestBidderId.isBlank())
                    ? "" : highestBidderId;
            BidOvertakenEvent overtakenEvent = new BidOvertakenEvent(
                    auctionId.toString(),
                    productName,
                    previousBidderId,
                    request.getBidPrice(),
                    request.getBidPrice() + bidUnit,
                    LocalDateTime.now()
            );
            bidEventProducer.sendBidOvertaken(overtakenEvent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("분산 락 해제 - auctionId: {}", auctionId);
            }
        }
    }

    public HighestBidResponse getHighestBid(UUID auctionId) {
        log.info("최고가 조회 요청 (폴백) - auctionId: {}", auctionId);

        Map<Object, Object> info = redisTemplate.opsForHash()
                .entries("auction:" + auctionId + ":info");

        if (info.isEmpty()) {
            throw new BidException(BidErrorCode.AUCTION_NOT_FOUND);
        }

        String highestBidderId = (String) info.get("highestBidderId");
        String currentPrice = (String) info.get("currentPrice");

        boolean hasBid = highestBidderId != null && !highestBidderId.isBlank();

        if (!hasBid) {
            log.info("입찰자 없음 - auctionId: {}", auctionId);
            return null;
        }

        log.info("최고가 조회 완료 - auctionId: {}, winnerId: {}, finalPrice: {}", auctionId, highestBidderId, currentPrice);
        return new HighestBidResponse(UUID.fromString(highestBidderId), Integer.parseInt(currentPrice));
    }
}
