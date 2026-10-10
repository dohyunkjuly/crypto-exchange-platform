package com.exchange.spot.openapi.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.core.api.exception.ApiException;
import com.exchange.core.ratelimit.RateLimiter;
import com.exchange.core.ratelimit.RateLimiter.Usage;
import com.exchange.spot.openapi.common.security.SignedRequest;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;
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
class OrderRateInterceptorTest {

    @Mock private RateLimiter limiter;

    static class Handlers {
        @CountsAsOrder public void place() { }
        public void cancel() { }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    private static MockHttpServletRequest signedAs(String apiKey) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/orders");
        r.setAttribute("signedRequest", new SignedRequest(UserApiKey.hmac(1L, "k", apiKey, new byte[] {1}, "v1"),
                new User("a@example.com", "h")));
        return r;
    }

    @Test
    @DisplayName("only a placement spends the order budget, per key, on both windows; a cancel spends nothing")
    void spendsOnPlacementOnly() throws Exception {
        OrderRateInterceptor interceptor = new OrderRateInterceptor(limiter, 100, 1000);
        when(limiter.charge(eq("ratelimit:oapi:orders10s:ak_1"), eq(1), eq(Duration.ofSeconds(10)))).thenReturn(new Usage(3, 7));
        when(limiter.charge(eq("ratelimit:oapi:orders1d:ak_1"), eq(1), eq(Duration.ofDays(1)))).thenReturn(new Usage(40, 80000));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(signedAs("ak_1"), response, handler("place"))).isTrue();
        assertThat(response.getHeader("X-ORDER-COUNT-10S")).isEqualTo("3");
        assertThat(response.getHeader("X-ORDER-COUNT-1D")).isEqualTo("40");

        assertThat(interceptor.preHandle(signedAs("ak_1"), new MockHttpServletResponse(), handler("cancel"))).isTrue();
        verify(limiter, never()).charge(eq("ratelimit:oapi:orders10s:ak_1"), eq(2), any());
    }

    @Test
    void refusesWhenEitherWindowIsSpent() throws Exception {
        OrderRateInterceptor interceptor = new OrderRateInterceptor(limiter, 100, 1000);
        when(limiter.charge(eq("ratelimit:oapi:orders10s:ak_2"), eq(1), any())).thenReturn(new Usage(50, 5));
        when(limiter.charge(eq("ratelimit:oapi:orders1d:ak_2"), eq(1), any())).thenReturn(new Usage(1001, 3600));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptor.preHandle(signedAs("ak_2"), response, handler("place")))
                .isInstanceOf(ApiException.class);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
    }
}
