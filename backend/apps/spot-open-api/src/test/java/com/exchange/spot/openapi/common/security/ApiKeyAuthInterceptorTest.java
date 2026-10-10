package com.exchange.spot.openapi.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.apikey.ApiKeyRevokedReason;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

/** Every step of the gate broken in turn, each shown to fail with its own code. */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthInterceptorTest {

    private static final UUID KEY_ID = UUID.fromString("01a0cf7b-cbda-168f-d757-e9d21c1c0696");

    private static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");
    private static final String SECRET = "s3cr3t";
    private static final byte[] BODY = "{\"symbol\":\"BTCUSDT\"}".getBytes(StandardCharsets.UTF_8);

    @Mock private ApiKeyCache cache;
    @Mock private ApiKeyService apiKeys;
    @Mock private UserRepository users;
    @Mock private EligibilityService eligibility;

    private ApiKeyAuthInterceptor interceptor;
    private UserApiKey key;
    private User user;

    /** Stand-ins for controller methods, so the annotations are real ones on real methods. */
    static class Handlers {
        public void read() { }
        public void account(@CurrentUser User user, @CurrentUser UserApiKey key) { }
        @RequiresPermission(Permission.TRADE) @RequiresTrading public void place() { }
        @RequiresPermission(Permission.TRADE) public void cancel() { }
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        interceptor = new ApiKeyAuthInterceptor(new SignedRequestAuthenticator(cache, apiKeys, new ObjectMapper(), clock),
                apiKeys, users, eligibility, clock);
        key = UserApiKey.hmac(42L, "bot", "ak_test", new byte[] {1}, "v1");
        ReflectionTestUtils.setField(key, "id", KEY_ID);
        key.setCanRead(true);
        key.setCanTrade(false);
        user = new User("alice@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setKycLevel(KycLevel.INTERMEDIATE);
    }

    private void keyKnown() {
        when(cache.find("ak_test")).thenReturn(Optional.of(key));
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        Method m = Arrays.stream(Handlers.class.getMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
        return new HandlerMethod(new Handlers(), m);
    }

    /** A correctly signed request for {@code method path?query} with {@code body}, as of NOW. */
    private CachedBodyRequest signed(String method, String pathWithQuery, byte[] body) throws Exception {
        String path = pathWithQuery.split("\\?", 2)[0];
        MockHttpServletRequest raw = new MockHttpServletRequest(method, path);
        if (pathWithQuery.contains("?")) {
            raw.setQueryString(pathWithQuery.split("\\?", 2)[1]);
        }
        raw.setContent(body);
        raw.setRemoteAddr("10.1.2.3");
        raw.addHeader(ApiKeyAuthInterceptor.KEY_HEADER, "ak_test");
        String ts = String.valueOf(NOW.toEpochMilli());
        raw.addHeader(ApiKeyAuthInterceptor.TIMESTAMP_HEADER, ts);
        raw.addHeader(ApiKeyAuthInterceptor.SIGNATURE_HEADER,
                HmacSignature.sign(SECRET, HmacSignature.canonical(ts, method, pathWithQuery, body)));
        return new CachedBodyRequest(raw);
    }

    private void assertCode(Runnable call, OpenApiErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void unknownKeyIsRefusedBeforeAnythingElseIsLookedAt() throws Exception {
        when(cache.find("ak_test")).thenReturn(Optional.empty());
        CachedBodyRequest request = signed("GET", "/v1/account", new byte[0]);
        assertCode(() -> run(request, "read"), OpenApiErrorCode.UNKNOWN_API_KEY);
        verify(apiKeys, never()).decryptSecret(any());
    }

    @Test
    void revokedOrExpiredKeyIs2106() throws Exception {
        keyKnown();
        key.revoke(ApiKeyRevokedReason.REVOKED_BY_USER, NOW.minusSeconds(1));
        CachedBodyRequest request = signed("GET", "/v1/account", new byte[0]);
        assertCode(() -> run(request, "read"), OpenApiErrorCode.API_KEY_INACTIVE);
    }

    @Test
    @DisplayName("a timestamp older than the receive window is a replay, and a window past the maximum is refused")
    void timestampWindow() throws Exception {
        keyKnown();
        CachedBodyRequest stale = signed("GET", "/v1/account", new byte[0]);
        ((MockHttpServletRequest) stale.getRequest()).removeHeader(ApiKeyAuthInterceptor.TIMESTAMP_HEADER);
        ((MockHttpServletRequest) stale.getRequest()).addHeader(ApiKeyAuthInterceptor.TIMESTAMP_HEADER,
                String.valueOf(NOW.minusSeconds(6).toEpochMilli()));
        assertCode(() -> run(stale, "read"), OpenApiErrorCode.TIMESTAMP_OUT_OF_WINDOW);

        CachedBodyRequest wideWindow = signed("GET", "/v1/account", new byte[0]);
        ((MockHttpServletRequest) wideWindow.getRequest()).addHeader(ApiKeyAuthInterceptor.RECV_WINDOW_HEADER, "61000");
        assertCode(() -> run(wideWindow, "read"), OpenApiErrorCode.TIMESTAMP_OUT_OF_WINDOW);
    }

    @Test
    void ipOutsideTheAllowlistIs2104() throws Exception {
        keyKnown();
        key.setIpAllowlist("[\"192.168.0.0/16\"]");
        CachedBodyRequest request = signed("GET", "/v1/account", new byte[0]);
        assertCode(() -> run(request, "read"), OpenApiErrorCode.IP_NOT_ALLOWED);
        verify(apiKeys, never()).decryptSecret(any());
    }

    @Test
    @DisplayName("the signature covers the body: a tampered body fails with 2102 and reveals nothing about permissions")
    void tamperedBodyIs2102() throws Exception {
        keyKnown();
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);
        CachedBodyRequest request = signed("POST", "/v1/orders", BODY);
        MockHttpServletRequest raw = (MockHttpServletRequest) request.getRequest();
        raw.setContent("{\"symbol\":\"ETHUSDT\"}".getBytes(StandardCharsets.UTF_8));
        CachedBodyRequest tampered = new CachedBodyRequest(raw);
        assertCode(() -> run(tampered, "place"), OpenApiErrorCode.INVALID_SIGNATURE);
    }

    @Test
    void queryStringIsPartOfWhatIsSigned() throws Exception {
        keyKnown();
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);
        CachedBodyRequest request = signed("GET", "/v1/orders?symbol=BTCUSDT", new byte[0]);
        ((MockHttpServletRequest) request.getRequest()).setQueryString("symbol=ETHUSDT");
        assertCode(() -> run(request, "read"), OpenApiErrorCode.INVALID_SIGNATURE);
    }

    @Test
    void aReadOnlyKeyCannotTradeOrCancel() throws Exception {
        keyKnown();
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);
        CachedBodyRequest request = signed("DELETE", "/v1/orders/x", new byte[0]);
        assertCode(() -> run(request, "cancel"), OpenApiErrorCode.PERMISSION_DENIED);
        verify(users, never()).findById(anyLong());
    }

    @Test
    @DisplayName("the account gate runs only where @RequiresTrading says, after the key checks, and its exception propagates for the advice")
    void tradingGateOnlyOnPlacement() throws Exception {
        keyKnown();
        key.setCanTrade(true);
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        doThrow(new KycRequiredException(KycLevel.BASIC)).when(eligibility).requireCanTrade(user);

        CachedBodyRequest placement = signed("POST", "/v1/orders", BODY);
        assertThatThrownBy(() -> run(placement, "place")).isInstanceOf(KycRequiredException.class);
        assertThat(run(signed("DELETE", "/v1/orders/x", new byte[0]), "cancel")).isTrue();
        verify(eligibility, times(1)).requireCanTrade(user);
    }

    @Test
    @DisplayName("a good request stashes key and user, and stamps last-used once a minute, not once a request")
    void successStashesAndTouchesOnce() throws Exception {
        keyKnown();
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);
        when(users.findById(42L)).thenReturn(Optional.of(user));

        CachedBodyRequest first = signed("GET", "/v1/account", new byte[0]);
        assertThat(run(first, "account")).isTrue();
        assertThat(first.getAttribute("signedRequest")).isEqualTo(new SignedRequest(key, user));
        run(signed("GET", "/v1/account", new byte[0]), "account");
        verify(apiKeys, times(1)).touchLastUsed(eq(KEY_ID), eq(NOW));
    }

    /** The key is cached and already names its owner, so the row is a read nothing here needs. */
    @Test
    @DisplayName("a cancel or a read runs on the cached key alone: the owner's row is not loaded")
    void theOwnerIsLoadedOnlyForWhatReadsIt() throws Exception {
        keyKnown();
        key.setCanTrade(true);
        when(apiKeys.decryptSecret(key)).thenReturn(SECRET);

        CachedBodyRequest cancel = signed("DELETE", "/v1/orders/x", new byte[0]);
        assertThat(run(cancel, "cancel")).isTrue();
        assertThat(run(signed("GET", "/v1/orders/open", new byte[0]), "read")).isTrue();

        verify(users, never()).findById(anyLong());
        assertThat(cancel.getAttribute("signedRequest")).isEqualTo(new SignedRequest(key, null));
    }

    private boolean run(CachedBodyRequest request, String handlerName) {
        try {
            return interceptor.preHandle(request, new MockHttpServletResponse(), handler(handlerName));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
