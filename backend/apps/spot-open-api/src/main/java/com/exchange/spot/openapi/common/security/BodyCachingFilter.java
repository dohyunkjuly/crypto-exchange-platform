package com.exchange.spot.openapi.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wraps every {@code /v1/**} request so {@link ApiKeyAuthInterceptor} can sign over the body
 *  and the controller can still read it. A public path pays for a small read it does not need,
 *  which is cheaper than a second dispatch decision here. */
@Component
public class BodyCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new CachedBodyRequest(request), response);
    }
}
