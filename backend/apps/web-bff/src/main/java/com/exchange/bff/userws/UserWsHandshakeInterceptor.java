package com.exchange.bff.userws;

import com.exchange.bff.common.security.TradingAccounts;
import com.exchange.core.api.exception.ApiException;
import org.springframework.web.util.UriComponentsBuilder;
import com.exchange.bff.auth.AuthService;
import com.exchange.bff.auth.SessionCredentials;
import com.exchange.user.identity.User;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Refuses the private socket to anyone without a valid session, and records whose it is.
 *
 * <p><b>Why a handshake interceptor and not the usual one.</b> {@code WebMvcConfig}'s
 * interceptors never run for a WebSocket upgrade -- {@code MarketWsHandler}'s javadoc notes
 * that the market socket is public precisely because nothing there applies. So the same
 * check has to be made again, here, at the only point in a socket's life where an HTTP
 * request exists.
 *
 * <p><b>The session, not a listen-key.</b> A venue issues an opaque key when its socket
 * lives on a different host, where a cookie would not be sent. This one is same-origin, so
 * the browser already presents the session cookie on the upgrade request, and the mobile app
 * sends the same token as a bearer header ({@link SessionCredentials}); there is no second
 * credential to mint, leak in a URL, or expire on its own schedule. It resolves
 * through {@link AuthService#validateSession} -- the same call {@code
 * AuthenticationInterceptor} makes, so a revoked session is refused here for the same reason
 * and at the same moment it would be over REST.
 *
 * <p>Rejecting with 401 rather than closing after connecting: a browser gets a failed
 * upgrade it can react to, instead of a socket that opens and then silently carries nothing.
 */
@Component
public class UserWsHandshakeInterceptor implements HandshakeInterceptor {

    /** Where the resolved id is left for {@link UserWsHandler} to read. */
    static final String USER_ID_ATTRIBUTE = "userId";

    /** The raw session token, kept so the handler can re-check it while the socket is open.
     *  Only the SHA-256 of it is ever stored anywhere; this copy lives in memory for the
     *  life of one connection and goes when it closes. Without it a logout could revoke
     *  every REST request while this stream carried on for hours. */
    static final String SESSION_TOKEN_ATTRIBUTE = "sessionToken";

    private static final Logger log = LoggerFactory.getLogger(UserWsHandshakeInterceptor.class);

    private final AuthService authService;
    private final TradingAccounts accounts;

    public UserWsHandshakeInterceptor(AuthService authService, TradingAccounts accounts) {
        this.authService = authService;
        this.accounts = accounts;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String rawToken = request instanceof ServletServerHttpRequest servlet
                ? SessionCredentials.read(servlet.getServletRequest()) : null;
        User user = rawToken == null ? null : authService.validateSession(rawToken).orElse(null);
        if (user == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // A customer watching their lead account connects a socket for it: the frames are
        // routed by account, and this is the one place a connection is given one.
        User account;
        try {
            account = accounts.resolve(user, UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().getFirst(TradingAccounts.PARAMETER));
        } catch (ApiException e) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        // The id, not the User: it is all the routing needs, and holding an entity for the
        // life of a connection would be a copy of a row going stale for hours.
        attributes.put(USER_ID_ATTRIBUTE, account.getId());
        attributes.put(SESSION_TOKEN_ATTRIBUTE, rawToken);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        if (exception != null) {
            log.debug("user WebSocket handshake failed", exception);
        }
    }
}
