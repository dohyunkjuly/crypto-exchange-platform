package com.exchange.spot.openapi.common.ratelimit;

import com.exchange.core.api.exception.ApiException;
import com.exchange.core.ratelimit.RateLimiter;
import com.exchange.core.ratelimit.RateLimiter.Usage;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The per-minute weight budget, counted per client IP, on every {@code /v1} route.
 *
 * <p>Per IP rather than per key, and first in the chain, deliberately: it runs before the
 * signature is checked, and at that point the key header is unverified text that a flood could
 * vary freely. An address is the one thing a caller cannot choose. A request-weight budget counts
 * the same way; the order budget, which needs an identity, is {@link OrderRateInterceptor}'s.
 *
 * <p>Every response carries {@code X-USED-WEIGHT-1M}; a refusal carries {@code Retry-After}. A
 * caller past the limit keeps being counted, so a retry loop lengthens its own wait.
 */
@Component
public class WeightInterceptor implements HandlerInterceptor {

    public static final String USED_HEADER = "X-USED-WEIGHT-1M";
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final int maxWeight;

    public WeightInterceptor(RateLimiter limiter,
                             @Value("${exchange.rate-limit.request-weight-per-minute}") int maxWeight) {
        this.limiter = limiter;
        this.maxWeight = maxWeight;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        int weight = weightOf(request, handler);
        Usage usage = limiter.charge("ratelimit:oapi:weight:" + request.getRemoteAddr(), weight, WINDOW);
        response.setHeader(USED_HEADER, String.valueOf(usage.used()));
        if (usage.used() > maxWeight) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, usage.secondsUntilReset())));
            throw new ApiException(OpenApiErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return true;
    }

    static int weightOf(HttpServletRequest request, Object handler) {
        Weight w = handler instanceof HandlerMethod m ? m.getMethodAnnotation(Weight.class) : null;
        if (w == null) {
            return 1;
        }
        if (w.perLimit() <= 0) {
            return w.value();
        }
        int limit;
        try {
            limit = Integer.parseInt(request.getParameter("limit"));
        } catch (NumberFormatException e) {
            return w.value(); // absent or unreadable: the controller's default applies, priced at the floor
        }
        return Math.max(w.value(), (int) Math.ceil(limit / (double) w.perLimit()));
    }
}
