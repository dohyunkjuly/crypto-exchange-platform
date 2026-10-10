package com.exchange.spot.openapi.common.ratelimit;

import com.exchange.core.api.exception.ApiException;
import com.exchange.core.ratelimit.RateLimiter;
import com.exchange.core.ratelimit.RateLimiter.Usage;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.spot.openapi.common.security.SignedRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The order budget: how many orders one key may place per ten seconds and per day. Runs after
 * authentication, because it counts per key and a key is only known to be real then. Only routes
 * marked {@link CountsAsOrder} spend it; cancels never do. Both counts go out on the response.
 */
@Component
public class OrderRateInterceptor implements HandlerInterceptor {

    public static final String TEN_SECONDS_HEADER = "X-ORDER-COUNT-10S";
    public static final String DAY_HEADER = "X-ORDER-COUNT-1D";

    private final RateLimiter limiter;
    private final int perTenSeconds;
    private final int perDay;

    public OrderRateInterceptor(RateLimiter limiter,
                                @Value("${exchange.rate-limit.orders-per-ten-seconds}") int perTenSeconds,
                                @Value("${exchange.rate-limit.orders-per-day}") int perDay) {
        this.limiter = limiter;
        this.perTenSeconds = perTenSeconds;
        this.perDay = perDay;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod m) || m.getMethodAnnotation(CountsAsOrder.class) == null
                || !(request.getAttribute("signedRequest") instanceof SignedRequest signed)) {
            return true;
        }
        String key = signed.key().getApiKey();
        Usage tenSeconds = limiter.charge("ratelimit:oapi:orders10s:" + key, 1, Duration.ofSeconds(10));
        Usage day = limiter.charge("ratelimit:oapi:orders1d:" + key, 1, Duration.ofDays(1));
        response.setHeader(TEN_SECONDS_HEADER, String.valueOf(tenSeconds.used()));
        response.setHeader(DAY_HEADER, String.valueOf(day.used()));
        if (tenSeconds.used() > perTenSeconds || day.used() > perDay) {
            long wait = tenSeconds.used() > perTenSeconds ? tenSeconds.secondsUntilReset() : day.secondsUntilReset();
            response.setHeader("Retry-After", String.valueOf(Math.max(1, wait)));
            throw new ApiException(OpenApiErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return true;
    }
}
