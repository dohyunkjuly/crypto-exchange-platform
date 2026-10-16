package com.exchange.bff.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Where a request carries its session: the cookie from the browser, {@code Authorization:
 * Bearer} from the mobile app. The same opaque token and the same row either way, so revoking
 * a session ends it for both.
 *
 * <p>The token reaches the app as a response header, and only when the login asks with
 * {@link #DELIVERY_HEADER}: a browser must never be handed a token its scripts can read,
 * which is the whole point of the httpOnly cookie.
 *
 * <p>A request with an {@code Authorization} header is judged by that header alone. Falling
 * back to a cookie would let a bad bearer pass on whatever cookie happened to ride along.
 */
public final class SessionCredentials {

    /** Sent by the app on a login: answer with {@link #TOKEN_HEADER}, not a cookie. */
    public static final String DELIVERY_HEADER = "X-Session-Delivery";

    public static final String TOKEN_HEADER = "X-Session-Token";

    private static final String BEARER_DELIVERY = "bearer";
    private static final String BEARER_PREFIX = "Bearer ";

    private SessionCredentials() {
    }

    /** The raw token, or null when the request carries none. */
    public static String read(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return null;
            }
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SessionCookies.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static boolean wantsBearer(HttpServletRequest request) {
        return BEARER_DELIVERY.equalsIgnoreCase(request.getHeader(DELIVERY_HEADER));
    }
}
