package com.exchange.bff.common.config;

import com.exchange.bff.common.security.AuthenticationInterceptor;
import com.exchange.bff.common.security.CurrentUserArgumentResolver;
import com.exchange.bff.common.security.TradingAccountArgumentResolver;
import com.exchange.bff.common.ratelimit.RateLimitInterceptor;
import com.exchange.bff.common.security.TradingEligibilityInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link RateLimitInterceptor} runs first (registration order is execution order) so a
 * flood gets rejected on a cheap Redis check before {@link AuthenticationInterceptor}'s
 * session lookup ever runs -- the latter is fail-closed enforcement (everything under
 * {@code /bff/**} requires a session unless explicitly excluded here); {@link
 * CurrentUserArgumentResolver} just hands the already-resolved user to a controller method
 * that asks for {@code @CurrentUser}.
 *
 * <p>Kept separate from {@link CorsConfig}: that one's about CORS, this one's about MVC
 * request handling, and they'd only share a class by coincidence.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthenticationInterceptor authenticationInterceptor;
    private final TradingEligibilityInterceptor tradingEligibilityInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final TradingAccountArgumentResolver tradingAccountArgumentResolver;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor,
                        AuthenticationInterceptor authenticationInterceptor,
                        TradingEligibilityInterceptor tradingEligibilityInterceptor,
                        CurrentUserArgumentResolver currentUserArgumentResolver,
                        TradingAccountArgumentResolver tradingAccountArgumentResolver) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.authenticationInterceptor = authenticationInterceptor;
        this.tradingEligibilityInterceptor = tradingEligibilityInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.tradingAccountArgumentResolver = tradingAccountArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // No exclusions -- unauthenticated endpoints (login, register) are exactly what
        // volume abuse targets hardest.
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/bff/**");

        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/bff/**")
                // Registration needs no prior session. Login mints one. Logout is
                // deliberately tolerant of an already-invalid/missing token -- it must
                // still succeed, not 401 -- so it can't sit behind this same gate.
                //
                // Market data is public for a different reason: what a market did is
                // nobody's private data, and gating the chart behind a session would show
                // a logged-out visitor an empty landing page. It stays behind the rate
                // limiter above, which is what actually matters for an unauthenticated
                // endpoint.
                // "/bff/auth/login/**", not just "/bff/auth/login": the second half of a
                // two-phase login is by definition made without a session, and an exact
                // pattern does not match the sub-path.
                .excludePathPatterns("/bff/auth/register", "/bff/auth/login",
                        "/bff/auth/login/**", "/bff/auth/session", "/bff/auth/google",
                        // By definition nobody resetting a password has a session.
                        "/bff/auth/password-reset", "/bff/auth/password-reset/**",
                        "/bff/market/**",
                        // Which markets exist, and their order rules. Public for the same
                        // reason the market data is: an exchange that needs a login to show
                        // what it lists has nothing to show a visitor.
                        "/bff/symbols",
                        // Who can be followed, public as the markets are: a leaderboard that
                        // needs a login has nothing to show a visitor. A copy account is not.
                        "/bff/copy/leaders", "/bff/copy/leaders/**");
        // Note what is NOT excluded: /bff/auth/verify-email. Verification is a code typed
        // into a session, not a link followed from an inbox, so it requires one -- which
        // is what proves the same person holds both the account and the mailbox.

        // Third, so the user AuthenticationInterceptor resolved is already in the request.
        // Scoped by the @RequiresTrading annotation rather than by path -- see that
        // annotation for why cancelling and reading must stay open to a lapsed account.
        registry.addInterceptor(tradingEligibilityInterceptor).addPathPatterns("/bff/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
        resolvers.add(tradingAccountArgumentResolver);
    }
}
