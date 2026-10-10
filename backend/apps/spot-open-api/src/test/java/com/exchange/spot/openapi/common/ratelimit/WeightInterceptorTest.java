package com.exchange.spot.openapi.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.exchange.core.api.exception.ApiException;
import com.exchange.core.ratelimit.RateLimiter;
import com.exchange.core.ratelimit.RateLimiter.Usage;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

@ExtendWith(MockitoExtension.class)
class WeightInterceptorTest {

    @Mock private RateLimiter limiter;

    static class Handlers {
        public void plain() { }
        @Weight(10) public void heavy() { }
        @Weight(value = 1, perLimit = 100) public void depth() { }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    @Test
    @DisplayName("the weight is the annotation's, scaled by the limit parameter when it says so")
    void weights() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/markets/BTCUSDT/depth");
        assertThat(WeightInterceptor.weightOf(r, handler("plain"))).isEqualTo(1);
        assertThat(WeightInterceptor.weightOf(r, handler("heavy"))).isEqualTo(10);
        assertThat(WeightInterceptor.weightOf(r, handler("depth"))).isEqualTo(1);
        r.setParameter("limit", "5000");
        assertThat(WeightInterceptor.weightOf(r, handler("depth"))).isEqualTo(50);
        r.setParameter("limit", "101");
        assertThat(WeightInterceptor.weightOf(r, handler("depth"))).isEqualTo(2);
        r.setParameter("limit", "junk");
        assertThat(WeightInterceptor.weightOf(r, handler("depth"))).isEqualTo(1);
    }

    @Test
    @DisplayName("counted per client IP; the running total goes out on every response, Retry-After only on a refusal")
    void countsPerIpAndReportsUsage() throws Exception {
        WeightInterceptor interceptor = new WeightInterceptor(limiter, 1200);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/time");
        r.setRemoteAddr("10.0.0.7");
        when(limiter.charge(eq("ratelimit:oapi:weight:10.0.0.7"), eq(1), any(Duration.class)))
                .thenReturn(new Usage(1199, 42)).thenReturn(new Usage(1201, 41));

        MockHttpServletResponse ok = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(r, ok, handler("plain"))).isTrue();
        assertThat(ok.getHeader("X-USED-WEIGHT-1M")).isEqualTo("1199");
        assertThat(ok.getHeader("Retry-After")).isNull();

        MockHttpServletResponse refused = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptor.preHandle(r, refused, handler("plain")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(OpenApiErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(refused.getHeader("X-USED-WEIGHT-1M")).isEqualTo("1201");
        assertThat(refused.getHeader("Retry-After")).isEqualTo("41");
    }
}
