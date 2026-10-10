package com.exchange.spot.openapi.ws;

import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.marketdata.stream.ProtocolErrors;
import com.exchange.marketdata.stream.SocketLimits;
import com.exchange.marketdata.stream.WsChannel;
import com.exchange.marketdata.stream.WsMessage;
import com.exchange.marketdata.stream.WsRequest;
import com.exchange.spot.openapi.common.errors.OpenApiWsErrorCode;
import com.exchange.spot.openapi.common.security.ApiKeyCache;
import com.exchange.spot.openapi.common.security.Permission;
import com.exchange.spot.openapi.common.security.SignedRequestAuthenticator;
import com.exchange.spot.openapi.common.security.SignedRequestAuthenticator.Credentials;
import com.exchange.spot.stream.UserFanout;
import com.exchange.user.apikey.UserApiKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The public API's socket at {@code /v1/ws}: the shared market handler plus the private half and
 * the connection rules the design doc tables (docs/reference/sockets.md).
 *
 * <p><b>The private half.</b> An {@code auth} message carries the key, a timestamp and a signature
 * over {@code timestamp + "GET" + "/v1/ws"} -- the same scheme as REST, checked by the same
 * {@link SignedRequestAuthenticator}. From then on every private frame for the account flows on
 * the connection with no subscribe step: the shared consumers hand frames to {@link #sendTo} and
 * this class delivers them to the account's authenticated connections. The user stream
 * works this way; a bot that wants orders wants fills and balances too.
 *
 * <p><b>Rules.</b> Ten inbound messages a second per connection (7009, closed after ten seconds
 * over). Twenty connections per IP and five authenticated per key, counted on this instance.
 * A 24-hour lifetime, closed with reason {@code reconnect}, so a rolling deploy never leaves a
 * socket pinned to an instance that is gone. A key revoked while its sockets are open is dropped
 * within a sweep: the cache re-reads the key every 30 s, so revocation bites in about a minute
 * rather than lasting until the client next reconnects.
 */
public class OpenApiWsHandler extends MarketWsHandler implements UserFanout {

    private static final Logger log = LoggerFactory.getLogger(OpenApiWsHandler.class);

    static final int MESSAGES_PER_SECOND = 10;
    static final int OVER_LIMIT_GRACE_SECONDS = 10;
    static final int CONNECTIONS_PER_KEY = 5;
    static final Duration LIFETIME = Duration.ofHours(24);
    static final String AUTH_PATH = "/v1/ws";
    static final CloseStatus RECONNECT = new CloseStatus(CloseStatus.NORMAL.getCode(), "reconnect");
    static final CloseStatus MESSAGE_RATE = new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "message rate");
    static final CloseStatus KEY_INACTIVE = new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "key inactive");

    private final ObjectMapper json;
    private final SignedRequestAuthenticator authenticator;
    private final ApiKeyCache keys;
    private final Clock clock;
    private final int connectionsPerIp;
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perKey = new ConcurrentHashMap<>();
    /** User id -> that user's authenticated connections: the lookup a private frame needs. */
    private final Map<Long, Set<ApiConnection>> byUser = new ConcurrentHashMap<>();

    public OpenApiWsHandler(ObjectMapper json, ProtocolErrors errors, SocketLimits limits, List<WsChannel> channels,
                            SignedRequestAuthenticator authenticator, ApiKeyCache keys, Clock clock,
                            int connectionsPerIp) {
        super(json, errors, limits, channels);
        this.json = json;
        this.authenticator = authenticator;
        this.keys = keys;
        this.clock = clock;
        this.connectionsPerIp = connectionsPerIp;
    }

    /** The shared connection plus this app's bookkeeping. */
    protected class ApiConnection extends Connection {
        final InboundBudget budget = new InboundBudget(MESSAGES_PER_SECOND, OVER_LIMIT_GRACE_SECONDS);
        final Instant openedAt = clock.instant();
        final String ip;
        /** Set by a successful {@code auth}; null while the connection is public only. */
        volatile UserApiKey key;

        ApiConnection(WebSocketSession session) {
            super(session);
            this.ip = ipOf(session);
        }

        /** In the subclass because {@code session} is protected in the shared class. */
        void deliver(TextMessage message) throws IOException {
            session.sendMessage(message);
        }
    }

    private static String ipOf(WebSocketSession session) {
        return session.getRemoteAddress() == null ? "unknown" : session.getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    protected Connection newConnection(WebSocketSession session) {
        return new ApiConnection(session);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String ip = ipOf(session);
        if (perIp.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() > connectionsPerIp) {
            release(perIp, ip);
            try {
                session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "too many connections"));
            } catch (Exception ignored) {
                // Refused before it was registered; nothing to clean up.
            }
            return;
        }
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session.getId());
        super.afterConnectionClosed(session, status);
        release(perIp, ipOf(session));
    }

    @Override
    protected void close(Connection connection, CloseStatus status) {
        forget(connection.id());
        super.close(connection, status);
        if (connection instanceof ApiConnection c) {
            release(perIp, c.ip);
        }
    }

    /** Drops the connection from the private index and the per-key count, if it had authenticated. */
    private void forget(String sessionId) {
        byUser.forEach((userId, connections) -> {
            connections.removeIf(c -> {
                if (!c.id().equals(sessionId)) {
                    return false;
                }
                if (c.key != null) {
                    release(perKey, c.key.getApiKey());
                }
                return true;
            });
            byUser.computeIfPresent(userId, (id, remaining) -> remaining.isEmpty() ? null : remaining);
        });
    }

    private static void release(Map<String, AtomicInteger> counts, String key) {
        counts.computeIfPresent(key, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    @Override
    protected boolean beforeDispatch(Connection connection, WsRequest request) {
        ApiConnection c = (ApiConnection) connection;
        return switch (c.budget.onMessage(clock.instant())) {
            case ALLOW -> true;
            case REFUSE -> {
                sendTo(c, OpenApiProtocolErrors.error(request.id(), OpenApiWsErrorCode.MESSAGE_RATE_EXCEEDED, null));
                yield false;
            }
            case DISCONNECT -> {
                close(c, MESSAGE_RATE);
                yield false;
            }
        };
    }

    /** {@code auth}: the signed-request checks, then the connection joins its account's set. A
     *  failure is thrown as the {@code ApiException} the authenticator raises, so it leaves with
     *  the same 2101-2105 code REST would give. */
    @Override
    protected boolean handleAction(Connection connection, WsRequest request) {
        if (!"auth".equals(request.action())) {
            return false;
        }
        ApiConnection c = (ApiConnection) connection;
        if (c.key != null) {
            sendTo(c, OpenApiProtocolErrors.error(request.id(), OpenApiWsErrorCode.ALREADY_AUTHENTICATED, null));
            return true;
        }
        UserApiKey key = authenticator.authenticate(
                new Credentials(request.apiKey(), request.timestamp(), null, request.signature()),
                c.ip, "GET", AUTH_PATH, new byte[0], Permission.READ);
        if (perKey.computeIfAbsent(key.getApiKey(), k -> new AtomicInteger()).incrementAndGet() > CONNECTIONS_PER_KEY) {
            release(perKey, key.getApiKey());
            sendTo(c, OpenApiProtocolErrors.error(request.id(), OpenApiWsErrorCode.TOO_MANY_CONNECTIONS,
                    "Too many connections for this key"));
            return true;
        }
        c.key = key;
        byUser.computeIfAbsent(key.getUserId(), id -> ConcurrentHashMap.newKeySet()).add(c);
        sendTo(c, WsMessage.reply(request.id(), "authenticated", Map.of("uid", key.getUserId())));
        return true;
    }

    /** The private consumers' door: one frame to every connection this account has authenticated
     *  here. Serialised once per user; a user with nothing open on this instance costs a map probe. */
    @Override
    public void sendTo(Long userId, WsMessage<?> payload) {
        Set<ApiConnection> connections = byUser.get(userId);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(payload));
        } catch (IOException e) {
            log.error("could not serialise a private frame for user {}", userId, e);
            return;
        }
        for (ApiConnection c : connections) {
            try {
                c.deliver(message);
            } catch (Exception e) {
                close(c, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }

    /** Sweeps for connections past their lifetime, and for authenticated ones whose key has since
     *  been revoked or expired. Once a minute is precision enough for both. */
    @Scheduled(fixedDelayString = "${exchange.open-api.ws.lifetime-sweep-ms:60000}")
    public void sweep() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(LIFETIME);
        for (Connection connection : List.copyOf(openConnections())) {
            if (!(connection instanceof ApiConnection c)) {
                continue;
            }
            if (c.openedAt.isBefore(cutoff)) {
                close(c, RECONNECT);
            } else if (c.key != null && keys.find(c.key.getApiKey()).filter(k -> k.isActive(now)).isEmpty()) {
                close(c, KEY_INACTIVE);
            }
        }
    }

    /** Test seam: how many authenticated connections this user has here. */
    public int authenticatedCount(Long userId) {
        Set<ApiConnection> connections = byUser.get(userId);
        return connections == null ? 0 : connections.size();
    }
}
