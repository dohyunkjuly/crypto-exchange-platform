package com.exchange.bff.common.ratelimit;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.core.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsUtils;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Global per-IP abuse guard for all of {@code /bff/**}, login and register included --
 * unauthenticated endpoints are exactly what a script hits hardest, and this runs before
 * {@link AuthenticationInterceptor} so a flood gets rejected on a cheap Redis counter check
 * before the (more expensive) session lookup ever runs.
 *
 * <p>Distinct from {@code AuthService}'s own login lockout: that one counts only failed
 * login attempts, keyed by email/IP, to stop credential stuffing. This counts every request
 * regardless of outcome, keyed by IP only, to stop raw volume abuse. Complementary, not
 * redundant.
 *
 * <p>Its own package rather than {@code common/security}, where it used to sit: this asks
 * how much traffic an address may send, not who it is or what it may do. Nobody looking
 * for rate limiting would guess "security". It also mirrors {@code libs/core}, which
 * already keeps {@code core/ratelimit} separate from {@code core/api}.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final int maxRequests;
    private final Duration window;

    /**
     * Configurable rather than constant, because a rate limit is an operational dial: when
     * something is hammering the API at 3am, tightening it should not require a deploy.
     * The defaults are the values this shipped with.
     */
    public RateLimitInterceptor(RateLimiter rateLimiter,
                                @Value("${exchange.rate-limit.max-requests:100}") int maxRequests,
                                @Value("${exchange.rate-limit.window-seconds:60}")
                                long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** @throws ApiException {@link BffHttpErrorCode#RATE_LIMIT_EXCEEDED} */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // The browser's own preflight must not spend the user's budget: one call from
        // web/ becomes two requests here, so counting both would silently halve every
        // quota and make the limit depend on whether a request happened to be preflighted.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        String key = "ratelimit:bff:" + request.getRemoteAddr();
        // weight 1 -- every request costs the same here. open-api's own interceptor will
        // pass a per-endpoint weight instead; see RateLimiter's javadoc.
        if (!rateLimiter.allow(key, 1, maxRequests, window)) {
            throw new ApiException(BffHttpErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return true;
    }
}
