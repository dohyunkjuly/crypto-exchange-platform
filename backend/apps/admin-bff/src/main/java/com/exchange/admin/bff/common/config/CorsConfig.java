package com.exchange.admin.bff.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** One origin, the admin/ app, with credentials: the cookie is httpOnly and every call is
 *  cross-origin, exactly as web-bff's CorsConfig explains. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${exchange.cors.allowed-origin}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/admin/**").allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PATCH", "DELETE").allowedHeaders("*").allowCredentials(true);
    }
}
