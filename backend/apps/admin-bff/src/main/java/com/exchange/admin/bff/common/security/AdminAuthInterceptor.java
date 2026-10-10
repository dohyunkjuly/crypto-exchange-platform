package com.exchange.admin.bff.common.security;

import com.exchange.admin.bff.auth.AdminAuthService;
import com.exchange.admin.bff.auth.AdminSessionCookie;
import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.session.AdminSessionState;
import com.exchange.core.api.exception.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fail-closed: everything under {@code /admin/**} needs an ACTIVE session unless
 * {@code WebMvcConfig} excludes it on purpose. Then the role, then the step-up, in that
 * order: an admin learns "you may not" before "type a code".
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String STEP_UP_HEADER = "X-Admin-TOTP";
    /** The login's second half runs on a PENDING session; nothing else does, not even /me. */
    static final String PENDING_ALLOWED_PREFIX = "/admin/auth/totp/";

    private final AdminAuthService auth;

    public AdminAuthInterceptor(AdminAuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        Authenticated who = cookie(request)
                .flatMap(auth::validate)
                .orElseThrow(() -> new ApiException(AdminErrorCode.NOT_AUTHENTICATED));
        if (who.session().getState() != AdminSessionState.ACTIVE
                && !request.getRequestURI().startsWith(PENDING_ALLOWED_PREFIX)) {
            throw new ApiException(AdminErrorCode.NOT_AUTHENTICATED);
        }
        if (handler instanceof HandlerMethod method) {
            RequiresRole role = method.getMethodAnnotation(RequiresRole.class);
            if (role != null && !who.admin().getRole().atLeast(role.value())) {
                throw new ApiException(AdminErrorCode.ROLE_REQUIRED);
            }
            if (method.getMethodAnnotation(RequiresStepUp.class) != null) {
                auth.stepUp(who.admin(), request.getHeader(STEP_UP_HEADER));
            }
        }
        request.setAttribute(Authenticated.ATTRIBUTE, who);
        return true;
    }

    private static Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> AdminSessionCookie.NAME.equals(c.getName()))
                .map(Cookie::getValue).findFirst();
    }
}
