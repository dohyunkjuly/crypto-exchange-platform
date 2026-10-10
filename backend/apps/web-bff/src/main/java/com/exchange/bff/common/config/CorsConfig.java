package com.exchange.bff.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the Next.js dev server on :3000 call this from the browser.
 *
 * <p>The allow-list is a single origin, not "*", because this app accepts orders.
 * A wildcard here would let any website on the internet submit trades using a
 * logged-in user's session.
 *
 * <p><b>{@code allowCredentials} is what makes the session work at all.</b> The session is
 * an httpOnly cookie and every call from {@code web/} is cross-origin, so without this the
 * browser sends no cookie and rejects the response even when one comes back -- every
 * authenticated endpoint answers NOT_AUTHENTICATED and nothing in the UI can explain why.
 * It is also why the wildcard above is not merely unwise but forbidden: the CORS spec
 * refuses {@code *} together with credentials, so this pairing has to be an explicit
 * origin.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${exchange.cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/bff/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                .allowCredentials(true);
    }
}
