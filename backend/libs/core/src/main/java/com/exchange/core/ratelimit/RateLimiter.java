package com.exchange.core.ratelimit;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A fixed-window counter in Redis, so every instance of an app shares one limit instead of
 * enforcing its own. {@code INCRBY} is atomic, so racing requests still count correctly
 * without locking.
 *
 * <p>Fixed window rather than sliding or a token bucket: a burst straddling two windows can
 * briefly allow 2x, which is acceptable for abuse prevention and not worth a Lua script.
 * This is not a billing quota.
 *
 * <p>{@code weight} is what lets one primitive serve both a per-request count (web-bff,
 * always 1) and a weighted budget (open-api, varies per endpoint).
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if {@code key} is still within {@code maxWeight} for the current window
     *         (and this call's {@code weight} counts toward it); false if the limit was
     *         already reached
     */
    public boolean allow(String key, int weight, int maxWeight, Duration window) {
        return charge(key, weight, window).used() <= maxWeight;
    }

    /** What a window holds after this call: the running total, and how long until it resets.
     *  Open-api reports both to the caller ({@code X-USED-WEIGHT-1M}, {@code Retry-After}). */
    public record Usage(long used, long secondsUntilReset) {
    }

    /** Counts {@code weight} against {@code key} whether or not the limit is already exceeded --
     *  a caller past the limit keeps spending, so hammering does not help. */
    public Usage charge(String key, int weight, Duration window) {
        Long total = redis.opsForValue().increment(key, weight);
        // total == weight implies previous == 0, so the key is new -- true whatever weight
        // is, which is why the expiry still fires exactly once per window.
        if (total != null && total == weight) {
            redis.expire(key, window);
        }
        Long ttl = redis.getExpire(key);
        return new Usage(total == null ? 0 : total, ttl == null || ttl < 0 ? window.toSeconds() : ttl);
    }
}
