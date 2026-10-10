package com.exchange.spot.openapi.common.config;

import com.exchange.spot.openapi.common.ratelimit.OrderRateInterceptor;
import com.exchange.spot.openapi.common.ratelimit.WeightInterceptor;
import com.exchange.spot.openapi.common.security.ApiKeyAuthInterceptor;
import com.exchange.spot.openapi.common.security.CurrentUserArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Everything under {@code /v1} is signed unless listed here as public -- fail-closed, so a new
 * endpoint is protected until someone decides otherwise. Public is what a market did and what
 * time it is: nobody's private data, and a bot needs the clock before it can sign anything.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final List<String> PUBLIC_PATHS = List.of("/v1/time", "/v1/markets/**");

    private final WeightInterceptor weight;
    private final ApiKeyAuthInterceptor auth;
    private final OrderRateInterceptor orderRate;
    private final CurrentUserArgumentResolver currentUser;

    public WebMvcConfig(WeightInterceptor weight, ApiKeyAuthInterceptor auth, OrderRateInterceptor orderRate,
                        CurrentUserArgumentResolver currentUser) {
        this.weight = weight;
        this.auth = auth;
        this.orderRate = orderRate;
        this.currentUser = currentUser;
    }

    /** Registration order is execution order: the cheap per-IP weight check first, so a flood is
     *  refused before a signature is ever computed; then the gate; then the per-key order budget,
     *  which needs the key the gate established. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(weight).addPathPatterns("/v1/**");
        registry.addInterceptor(auth).addPathPatterns("/v1/**").excludePathPatterns(PUBLIC_PATHS);
        registry.addInterceptor(orderRate).addPathPatterns("/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }
}
