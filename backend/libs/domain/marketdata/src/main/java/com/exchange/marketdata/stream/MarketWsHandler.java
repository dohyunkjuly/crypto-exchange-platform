package com.exchange.marketdata.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The live market socket: holds the open browser connections and fans frames out to them.
 *
 * <p>Shared by web-bff and spot-open-api, each of which constructs it as its own bean (not a
 * scanned component: open-api extends it, and two beans of the type would make every publisher's
 * {@link MarketFanout} ambiguous) on its own path, with its own {@link ProtocolErrors} and
 * {@link SocketLimits}. The protocol, deliberately not the usual stream names:
 *
 * <pre>
 *   client -&gt; {"action":"subscribe","channel":"kline","symbol":"BTCUSDT","interval":"1m"}
 *   server -&gt; {"type":"subscribed","data":{"channel":"kline","symbol":"BTCUSDT","interval":"1m"}}
 *   server -&gt; {"type":"kline","data":{"symbol":"BTCUSDT","interval":"1m","close":"60000", ...}}
 * </pre>
 *
 * <p>Switching symbol or channel is another message on the same socket, not a reconnect, and a
 * page holds several subscriptions at once -- so a {@link Subscription} is a channel and a
 * symbol together, a symbol alone having stopped being enough the moment there were two things
 * to send. The {@link WsChannel} beans are the set of valid channels, so an unknown one is
 * refused in one place.
 *
 * <p><b>Trades, not candles.</b> The client folds each trade into its current bar and opens a
 * new one at the bucket boundary. Aggregating here would be a second implementation of
 * market-data's bucketing, and two implementations of the same arithmetic eventually disagree.
 *
 * <p>Public, like the REST market endpoints -- what a market did is nobody's private data. Note
 * the handshake bypasses {@code WebMvcConfig}'s interceptors entirely, so neither the auth gate
 * nor the rate limiter applies: {@link #MAX_SYMBOLS_PER_CONNECTION} and the send-buffer limit
 * are this endpoint's own guards ({@link SocketLimits}).
 */
public class MarketWsHandler extends TextWebSocketHandler implements MarketFanout {

    private static final Logger log = LoggerFactory.getLogger(MarketWsHandler.class);


    private final ObjectMapper json;
    private final ProtocolErrors errors;
    private final SocketLimits limits;

    /** Channel name -&gt; the channel. Fixed at startup; every valid name is a bean. */
    private final Map<String, WsChannel> channels;

    /** Session id -&gt; connection. */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /** Subscription -&gt; the connections holding it. The lookup a broadcast actually needs,
     *  so an event in an unwatched market costs one map probe and nothing else. */
    private final Map<Subscription, Set<Connection>> bySubscription = new ConcurrentHashMap<>();

    public MarketWsHandler(ObjectMapper json, ProtocolErrors errors, SocketLimits limits,
                           List<WsChannel> channels) {
        this.json = json;
        this.errors = errors;
        this.limits = limits;
        // Keyed by the lower-cased name: WsRequest lower-cases what a client sends, so a channel
        // named in camel case (depthUpdate) is still found. The subscription then carries the
        // channel's own name, which is what its publisher broadcasts on.
        this.channels = channels.stream()
                .collect(Collectors.toUnmodifiableMap(c -> c.name().toLowerCase(java.util.Locale.ROOT), Function.identity()));
    }

    /** One open socket and what it watches. Protected so an app's subclass can attach its own
     *  state to it, such as who authenticated on it. */
    protected static class Connection {
        protected final WebSocketSession session;
        private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

        protected Connection(WebSocketSession session) {
            this.session = session;
        }

        public String id() {
            return session.getId();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // WebSocketSession.sendMessage is NOT thread-safe: two threads sending at once
        // interleave frames and corrupt the stream. Every send here comes from a Kafka
        // listener thread or the heartbeat, so wrapping is not optional.
        connections.put(session.getId(), newConnection(
                new ConcurrentWebSocketSessionDecorator(session, limits.sendTimeLimitMs(),
                        limits.sendBufferLimitBytes())));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        WsRequest request;
        try {
            request = json.readValue(message.getPayload(), WsRequest.class);
        } catch (IOException e) {
            // Nothing to correlate against: the frame that would have carried an id is
            // the one that failed to parse.
            send(connection, errors.malformed());
            return;
        }

        try {
            if (!beforeDispatch(connection, request)) {
                return;
            }
            dispatch(connection, request);
        } catch (SymbolRequired e) {
            send(connection, errors.symbolRequired(request.id()));
        } catch (Exception e) {
            send(connection, errors.render(request.id(), e));
        }
    }

    /** Every failure -- a channel's plain exception, a domain one, or a bug -- leaves through
     *  {@link ProtocolErrors#render}, so the app that owns the socket names the code. */
    private void dispatch(Connection connection, WsRequest request) {
        switch (request.action()) {
            case "subscribe" -> { requireSymbol(connection, request); subscribe(connection, request); }
            case "unsubscribe" -> { requireSymbol(connection, request); unsubscribe(connection, request); }
            default -> {
                if (!handleAction(connection, request)) {
                    send(connection, errors.unknownAction(request.id(), request.action()));
                }
            }
        }
    }

    private void requireSymbol(Connection connection, WsRequest request) {
        if (request.symbol().isBlank()) {
            throw new SymbolRequired();
        }
    }

    /** Thrown inside dispatch so the one catch renders it; never leaves this class. */
    private static final class SymbolRequired extends RuntimeException {
    }

    // ---- Hooks for an app that extends the protocol (open-api's `auth`, its rate limits) ----

    /** The connection object for a new socket; override to attach app state. */
    protected Connection newConnection(WebSocketSession session) {
        return new Connection(session);
    }

    /** Runs before any action is dispatched. Return false to swallow the request (after
     *  sending whatever the app wants), true to continue. */
    protected boolean beforeDispatch(Connection connection, WsRequest request) {
        return true;
    }

    /** An action this class does not know. Return true if the app handled it. */
    protected boolean handleAction(Connection connection, WsRequest request) {
        return false;
    }

    /** Sends one frame to one connection. For a subclass's own replies. */
    protected void sendTo(Connection connection, WsMessage<?> payload) {
        send(connection, payload);
    }

    /** Every open connection, for a subclass that sweeps them (a lifetime cap, say). */
    protected java.util.Collection<Connection> openConnections() {
        return connections.values();
    }

    /** Closes a connection with a reason and forgets it. */
    protected void close(Connection connection, CloseStatus status) {
        connections.remove(connection.session.getId());
        for (Subscription subscription : connection.subscriptions) {
            detach(connection, subscription);
        }
        try {
            connection.session.close(status);
        } catch (Exception ignored) {
            // Already closed.
        }
    }

    private void subscribe(Connection connection, WsRequest request) {
        WsChannel channel = channels.get(request.channel());
        if (channel == null) {
            send(connection, errors.unknownChannel(request.id(), request.channel()));
            return;
        }

        Subscription subscription = subscriptionFor(request, channel.name());
        // Before anything is recorded or acknowledged: a client that got a `subscribed`
        // frame and then an error would have to work out which of its subscriptions took.
        channel.validate(subscription);

        if (!connection.subscriptions.contains(subscription)
                && connection.subscriptions.size() >= limits.maxSubscriptionsPerConnection()) {
            send(connection, errors.tooManySubscriptions(request.id()));
            return;
        }
        connection.subscriptions.add(subscription);
        bySubscription.computeIfAbsent(subscription, s -> ConcurrentHashMap.newKeySet())
                .add(connection);
        send(connection, WsMessage.reply(request.id(), "subscribed", dataFor(subscription)));

        // After the acknowledgement, so a client sees the reply to its request before any
        // data arrives on the back of it. A channel carrying state opens with that state;
        // one carrying events opens with nothing. See WsChannel#initialFrame.
        channel.initialFrame(subscription).ifPresent(frame -> send(connection, frame));
    }

    private void unsubscribe(Connection connection, WsRequest request) {
        WsChannel channel = channels.get(request.channel());
        Subscription subscription = subscriptionFor(request, channel == null ? request.channel() : channel.name());
        connection.subscriptions.remove(subscription);
        detach(connection, subscription);
        send(connection, WsMessage.reply(request.id(), "unsubscribed", dataFor(subscription)));
    }

    private static Subscription subscriptionFor(WsRequest request, String channelName) {
        return new Subscription(channelName, request.symbol(), request.interval(),
                request.grouping());
    }

    private static SubscriptionData dataFor(Subscription subscription) {
        return new SubscriptionData(subscription.channel(), subscription.symbol(),
                subscription.interval(), subscription.grouping());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        if (connection == null) {
            return;
        }
        for (Subscription subscription : connection.subscriptions) {
            detach(connection, subscription);
        }
    }

    /**
     * Fans one message out to everyone holding this exact subscription.
     *
     * <p>Serialised once for the whole symbol, not once per viewer -- with thousands of
     * connections on one market that difference is the entire cost of the broadcast.
     * Returns immediately when nobody is watching, which is the common case for most
     * symbols, so an idle market costs a single map lookup.
     */
    @Override
    public void broadcast(Subscription subscription, WsMessage<?> payload) {
        Set<Connection> watching = bySubscription.get(subscription);
        if (watching == null || watching.isEmpty()) {
            return;
        }
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(payload));
        } catch (IOException e) {
            log.error("could not serialise a market WebSocket message for {}", subscription, e);
            return;
        }
        for (Connection connection : watching) {
            deliver(connection, message);
        }
    }

    /**
     * Keeps idle connections alive.
     *
     * <p>A market with no trades sends nothing for minutes, and load balancers close idle
     * connections well before that -- 60s is a common default on nginx and ALB. Without
     * this, quiet markets would look like broken ones.
     */
    @Scheduled(fixedDelayString = "${exchange.market-ws.heartbeat-ms:30000}")
    public void heartbeat() {
        for (Connection connection : connections.values()) {
            try {
                connection.session.sendMessage(new PingMessage());
            } catch (Exception e) {
                drop(connection, e);
            }
        }
    }

    private void deliver(Connection connection, TextMessage message) {
        try {
            connection.session.sendMessage(message);
        } catch (Exception e) {
            drop(connection, e);
        }
    }

    private void send(Connection connection, WsMessage<?> payload) {
        try {
            connection.session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
        } catch (Exception e) {
            drop(connection, e);
        }
    }

    /** A send failure means this connection is gone or hopelessly behind; take it out of
     *  the fan-out rather than failing on it again for every subsequent trade. */
    private void drop(Connection connection, Exception cause) {
        log.debug("dropping market WebSocket connection {}", connection.session.getId(), cause);
        connections.remove(connection.session.getId());
        for (Subscription subscription : connection.subscriptions) {
            detach(connection, subscription);
        }
        try {
            connection.session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception ignored) {
            // Already closed, which is exactly the case that got us here.
        }
    }

    /** Removes the subscription's entry entirely once its last watcher leaves, so the map
     *  does not accumulate a key per pair anyone has ever looked at. */
    private void detach(Connection connection, Subscription subscription) {
        bySubscription.computeIfPresent(subscription, (s, watchers) -> {
            watchers.remove(connection);
            return watchers.isEmpty() ? null : watchers;
        });
    }

    /**
     * The subscriptions on this channel that have at least one subscriber right now.
     *
     * <p>What the publishers poll. Deliberately only what this instance is actually
     * serving: a market nobody here is watching costs no read, which is the common case for
     * most symbols most of the time. Returned as whole subscriptions rather than symbols
     * because a channel can be watched at several intervals at once.
     */
    @Override
    public Set<Subscription> watched(String channel) {
        Set<Subscription> matching = new HashSet<>();
        for (Subscription subscription : bySubscription.keySet()) {
            if (subscription.channel().equals(channel)) {
                matching.add(subscription);
            }
        }
        return matching;
    }

    /** Test seam: how many connections hold this exact subscription right now. */
    public int subscriberCount(Subscription subscription) {
        Set<Connection> watching = bySubscription.get(subscription);
        return watching == null ? 0 : watching.size();
    }
}
