package com.exchange.bff.userws;

import com.exchange.bff.auth.AuthService;
import com.exchange.marketdata.stream.WsMessage;
import com.exchange.spot.stream.UserFanout;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The private socket: holds each signed-in user's connections and pushes their own order
 * updates to them.
 *
 * <p><b>No subscribe message.</b> A market socket is asked what to watch; this one already
 * knows -- the handshake established who you are, and you get your own events and nothing
 * else. There is no channel to name and no symbol to choose, so the protocol has no inbound
 * shape at all. Anything a client sends is ignored rather than answered, because a reply
 * would imply a request vocabulary that does not exist.
 *
 * <p><b>Routed by user, not by symbol.</b> That is the whole reason this is a separate
 * handler rather than another channel on {@code MarketWsHandler}: the index is
 * {@code userId -> connections}, the auth is required rather than absent, and a frame going
 * to the wrong recipient is a breach here where it would merely be noise there.
 *
 * <p>The connection bookkeeping below is close to {@code MarketWsHandler}'s and is copied
 * rather than shared. Two near-identical copies is the cheaper mistake at this size: the
 * alternative is a base class that both must fit, and they already differ in the parts that
 * matter -- one has subscriptions and no identity, the other identity and no subscriptions.
 * A third socket would be the moment to reconsider.
 */
@Component
public class UserWsHandler extends TextWebSocketHandler implements UserFanout {

    private static final Logger log = LoggerFactory.getLogger(UserWsHandler.class);

    /** A slow client must never hold up the Kafka thread delivering to everyone else. The
     *  decorator buffers instead of blocking and closes a connection that falls this far
     *  behind -- dropping one tab rather than degrading delivery for every user. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    private final ObjectMapper json;
    private final AuthService authService;

    /** User id -&gt; that user's open connections. The lookup an event actually needs, so an
     *  order belonging to nobody connected here costs one map probe. Several connections per
     *  user is normal: a second tab is a second entry. */
    private final Map<Long, Set<Connection>> byUser = new ConcurrentHashMap<>();

    public UserWsHandler(ObjectMapper json, AuthService authService) {
        this.json = json;
        this.authService = authService;
    }

    private record Connection(Long userId, String rawSessionToken, WebSocketSession session) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes()
                .get(UserWsHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (userId == null) {
            // Cannot happen while the handshake interceptor is registered, and is a
            // catastrophe if it ever does: an unattributed connection would either receive
            // nothing or, worse, be routed something. Refuse rather than guess.
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        // WebSocketSession.sendMessage is NOT thread-safe: two threads sending at once
        // interleave frames and corrupt the stream. Every send here is from a Kafka
        // listener thread or the heartbeat, so wrapping is not optional.
        Connection connection = new Connection(userId,
                (String) session.getAttributes()
                        .get(UserWsHandshakeInterceptor.SESSION_TOKEN_ATTRIBUTE),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                        SEND_BUFFER_LIMIT_BYTES));
        byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        detach(session.getId());
    }

    /**
     * Sends one message to every connection this user has open.
     *
     * <p>Serialised once per user rather than once per tab. Returns immediately when the
     * user has nothing open here, which is the common case on any instance that is not
     * theirs -- every instance reads every event, so most of them belong to somebody else.
     */
    @Override
    public void sendTo(Long userId, WsMessage<?> payload) {
        Set<Connection> connections = byUser.get(userId);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(payload));
        } catch (IOException e) {
            log.error("could not serialise a user WebSocket message for user {}", userId, e);
            return;
        }
        for (Connection connection : connections) {
            deliver(connection, message);
        }
    }

    /**
     * Keeps idle connections alive, and closes ones whose session has ended.
     *
     * <p>The second job is the one that matters. A handshake checks the cookie once, and a
     * socket then lives for hours -- so without this, logging out or changing a password
     * would revoke every REST request while a stream of the same account's orders kept
     * flowing to a browser that is no longer signed in. Re-validating on a beat that is
     * already ticking costs one indexed lookup per connection per interval.
     */
    @Scheduled(fixedDelayString = "${exchange.user-ws.heartbeat-ms:30000}")
    public void heartbeat() {
        for (Set<Connection> connections : byUser.values()) {
            for (Connection connection : connections) {
                if (connection.rawSessionToken() == null
                        || authService.validateSession(connection.rawSessionToken()).isEmpty()) {
                    log.debug("closing a user socket whose session is no longer valid");
                    drop(connection, CloseStatus.POLICY_VIOLATION);
                    continue;
                }
                try {
                    connection.session().sendMessage(new PingMessage());
                } catch (Exception e) {
                    drop(connection, CloseStatus.SESSION_NOT_RELIABLE);
                }
            }
        }
    }

    private void deliver(Connection connection, TextMessage message) {
        try {
            connection.session().sendMessage(message);
        } catch (Exception e) {
            log.debug("dropping user WebSocket connection {}", connection.session().getId(), e);
            drop(connection, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void drop(Connection connection, CloseStatus status) {
        detach(connection.session().getId());
        close(connection.session(), status);
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // Already closed, which is often exactly the case that got us here.
        }
    }

    /** Removes a user's entry entirely once their last connection goes, so the map does not
     *  accumulate a key per user who has ever connected to this instance. */
    private void detach(String sessionId) {
        byUser.forEach((userId, connections) -> {
            connections.removeIf(connection -> connection.session().getId().equals(sessionId));
            byUser.computeIfPresent(userId, (id, remaining) ->
                    remaining.isEmpty() ? null : remaining);
        });
    }

    /** Test seam: how many connections this user has open on this instance. */
    int connectionCount(Long userId) {
        Set<Connection> connections = byUser.get(userId);
        return connections == null ? 0 : connections.size();
    }
}
