package com.exchange.spot.openapi.common.security;

import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.UserApiKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The key-level checks of a signed request, in the order that costs least and reveals least: a
 * key exists and is active, the timestamp is inside the receive window, the client IP is on the
 * allowlist, the signature verifies, the key holds the permission. Shared by the REST
 * interceptor and the socket's {@code auth}, which sign the same way over different paths.
 *
 * <p>Says nothing about the account: that is {@code EligibilityService}'s layer, applied by the
 * caller where placing an order needs it.
 */
@Component
public class SignedRequestAuthenticator {

    static final Duration DEFAULT_RECV_WINDOW = Duration.ofSeconds(5);
    static final Duration MAX_RECV_WINDOW = Duration.ofSeconds(60);

    /** The headers, or the {@code auth} message's fields, as one value object. */
    public record Credentials(String apiKey, String timestamp, String recvWindow, String signature) {
    }

    private final ApiKeyCache keyCache;
    private final ApiKeyService apiKeys;
    private final ObjectMapper json;
    private final Clock clock;

    public SignedRequestAuthenticator(ApiKeyCache keyCache, ApiKeyService apiKeys, ObjectMapper json, Clock clock) {
        this.keyCache = keyCache;
        this.apiKeys = apiKeys;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @return the key, once every check has passed
     * @throws ApiException 2101 unknown key, 2106 inactive, 2103 timestamp, 2104 IP, 2102 signature,
     *         2105 permission -- each its own code so a client can tell what to fix
     */
    public UserApiKey authenticate(Credentials c, String remoteAddress, String method, String pathWithQuery,
                                   byte[] body, Permission needed) {
        Instant now = clock.instant();
        UserApiKey key = c.apiKey() == null || c.apiKey().isEmpty() ? null : keyCache.find(c.apiKey()).orElse(null);
        if (key == null) {
            throw new ApiException(OpenApiErrorCode.UNKNOWN_API_KEY);
        }
        if (!key.isActive(now)) {
            throw new ApiException(OpenApiErrorCode.API_KEY_INACTIVE);
        }
        if (!withinWindow(c.timestamp(), c.recvWindow(), now)) {
            throw new ApiException(OpenApiErrorCode.TIMESTAMP_OUT_OF_WINDOW);
        }
        if (!allowed(key, remoteAddress)) {
            throw new ApiException(OpenApiErrorCode.IP_NOT_ALLOWED);
        }
        String canonical = HmacSignature.canonical(c.timestamp(), method, pathWithQuery, body);
        if (!HmacSignature.verify(apiKeys.decryptSecret(key), canonical, c.signature())) {
            throw new ApiException(OpenApiErrorCode.INVALID_SIGNATURE);
        }
        if (needed == Permission.TRADE ? !key.isCanTrade() : !key.isCanRead()) {
            throw new ApiException(OpenApiErrorCode.PERMISSION_DENIED);
        }
        return key;
    }

    private static boolean withinWindow(String timestamp, String recvWindow, Instant now) {
        long ts;
        long window;
        try {
            ts = Long.parseLong(timestamp);
            window = recvWindow == null || recvWindow.isEmpty() ? DEFAULT_RECV_WINDOW.toMillis() : Long.parseLong(recvWindow);
        } catch (NumberFormatException e) {
            return false;
        }
        if (window <= 0 || window > MAX_RECV_WINDOW.toMillis()) {
            return false;
        }
        return Math.abs(now.toEpochMilli() - ts) <= window;
    }

    /** No allowlist means any address. A malformed list admits nobody: see {@link Cidr}. */
    private boolean allowed(UserApiKey key, String address) {
        if (key.getIpAllowlist() == null) {
            return true;
        }
        List<String> entries;
        try {
            entries = json.readValue(key.getIpAllowlist(), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return false;
        }
        return entries.stream().anyMatch(entry -> Cidr.contains(entry, address));
    }
}
