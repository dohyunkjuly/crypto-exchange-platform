package com.exchange.bff.common.security;

import com.exchange.bff.auth.AuthService;
import com.exchange.bff.auth.SessionCookies;
import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import org.springframework.web.cors.CorsUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fail-closed, unlike {@code @CurrentUser} on its own: registered in {@code WebMvcConfig}
 * against {@code /bff/**}, so a new endpoint is protected by default and has to be
 * deliberately excluded to be public, rather than staying accidentally open because nobody
 * remembered to add a parameter. Runs before the controller method; throwing here means the
 * method body never executes.
 *
 * <p>Resolves the session once and stashes it on the request -- {@link
 * CurrentUserArgumentResolver} reads it back rather than validating the session a second
 * time.
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    static final String CURRENT_USER_ATTRIBUTE = "currentUser";

    private final AuthService authService;

    public AuthenticationInterceptor(AuthService authService) {
        this.authService = authService;
    }

    /** @throws ApiException {@link BffHttpErrorCode#NOT_AUTHENTICATED} missing, invalid,
     *          expired, or revoked session */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // A CORS preflight carries no cookies -- browsers never send credentials on one --
        // so demanding a session here fails the preflight and the real request is never
        // sent at all. The browser reports it as a CORS error with no mention of
        // authentication, which is about as misleading as a failure can be. The preflight
        // reaches no controller, so letting it past is not a gap: Spring answers it with
        // the allowed methods and headers and nothing else.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        String rawToken = readCookie(request);
        User user = rawToken == null ? null : authService.validateSession(rawToken).orElse(null);
        if (user == null) {
            throw new ApiException(BffHttpErrorCode.NOT_AUTHENTICATED);
        }
        request.setAttribute(CURRENT_USER_ATTRIBUTE, user);
        return true;
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SessionCookies.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
