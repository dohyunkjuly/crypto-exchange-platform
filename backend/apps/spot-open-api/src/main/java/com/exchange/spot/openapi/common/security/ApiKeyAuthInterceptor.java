package com.exchange.spot.openapi.common.security;

import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The signed-request gate on every {@code /v1/**} route not excluded in {@code WebMvcConfig}.
 *
 * <p>Steps, each with its own code so a client can tell what to fix, in the order that costs
 * least first and reveals least to a stranger: an unknown key is refused before anything about
 * the account is looked at, and the signature is checked before the permission, so a caller
 * without the secret learns nothing about what the key may do.
 *
 * <ol>
 *   <li>key exists → 2101; active (not revoked, not expired) → 2106
 *   <li>|now − timestamp| ≤ recvWindow → 2103. One check covers replay and clock drift.
 *   <li>client IP on the allowlist, when one is set → 2104
 *   <li>signature over timestamp + method + path?query + body → 2102
 *   <li>the endpoint's {@link RequiresPermission} against the key's flags → 2105
 *   <li>{@link RequiresTrading} only: the account may trade right now → 2107 / 2108 / 2109.
 *       The owner's row is read for this, and for a controller that asks for the {@code User};
 *       every other request runs on the cached key alone and touches no table here.
 *   <li>last-used stamp, at most once a minute per key
 * </ol>
 *
 * <p>Two independent layers deliberately: the key gates what this credential may do; the
 * account gate what its owner may do today. A revoked key fails at 1; a key whose owner's KYC
 * lapsed fails at 6, and only when placing an order.
 *
 * <p>Fail-closed like web-bff's session interceptor: a new route under {@code /v1} is signed
 * unless someone excludes it on purpose.
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    public static final String KEY_HEADER = "X-ACCESS-KEY";
    public static final String TIMESTAMP_HEADER = "X-ACCESS-TIMESTAMP";
    public static final String RECV_WINDOW_HEADER = "X-ACCESS-RECV-WINDOW";
    public static final String SIGNATURE_HEADER = "X-ACCESS-SIGN";

    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SignedRequestAuthenticator authenticator;
    private final ApiKeyService apiKeys;
    private final UserRepository users;
    private final EligibilityService eligibility;
    private final Clock clock;
    /** When each key's last-used stamp was last written from this instance. */
    private final Map<UUID, Instant> touched = new ConcurrentHashMap<>();

    public ApiKeyAuthInterceptor(SignedRequestAuthenticator authenticator, ApiKeyService apiKeys, UserRepository users,
                                 EligibilityService eligibility, Clock clock) {
        this.authenticator = authenticator;
        this.apiKeys = apiKeys;
        this.users = users;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Instant now = clock.instant();
        Permission needed = handler instanceof HandlerMethod method
                && method.getMethodAnnotation(RequiresPermission.class) != null
                ? method.getMethodAnnotation(RequiresPermission.class).value()
                : Permission.READ;
        byte[] body = request instanceof CachedBodyRequest cached ? cached.body() : new byte[0];
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        UserApiKey key = authenticator.authenticate(new SignedRequestAuthenticator.Credentials(
                        request.getHeader(KEY_HEADER), request.getHeader(TIMESTAMP_HEADER),
                        request.getHeader(RECV_WINDOW_HEADER), request.getHeader(SIGNATURE_HEADER)),
                request.getRemoteAddr(), request.getMethod(), path, body, needed);

        // The key's owner is a database read, and the key, which is cached, already names them:
        // a cancel or a read needs the id and nothing else. So the row is loaded only for what
        // reads it -- the trading gate, and a controller that declares a @CurrentUser User --
        // which left every other request one transaction lighter.
        boolean gated = handler instanceof HandlerMethod method
                && method.getMethodAnnotation(RequiresTrading.class) != null;
        User user = null;
        if (gated || declaresCurrentUser(handler)) {
            user = users.findById(key.getUserId())
                    .orElseThrow(() -> new ApiException(OpenApiErrorCode.UNKNOWN_API_KEY));
        }
        if (gated) {
            eligibility.requireCanTrade(user); // its exceptions are mapped by OpenApiEligibilityAdvice
        }

        touch(key.getId(), now);
        request.setAttribute(SignedRequest.ATTRIBUTE, new SignedRequest(key, user));
        return true;
    }

    private static boolean declaresCurrentUser(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        for (MethodParameter parameter : method.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(CurrentUser.class)
                    && User.class.isAssignableFrom(parameter.getParameterType())) {
                return true;
            }
        }
        return false;
    }

    private void touch(UUID keyId, Instant now) {
        Instant last = touched.get(keyId);
        if (last == null || last.plus(TOUCH_INTERVAL).isBefore(now)) {
            touched.put(keyId, now);
            apiKeys.touchLastUsed(keyId, now);
        }
    }
}
