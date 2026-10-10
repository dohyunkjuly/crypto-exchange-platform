package com.exchange.bff.common.config;

import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.bff.userws.UserWsHandler;
import com.exchange.bff.userws.UserWsHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the live market socket at {@code /bff/ws/market}.
 *
 * <p>Raw WebSocket, not STOMP: the protocol here is three message types (see
 * {@link MarketWsHandler}), and STOMP would add a broker abstraction, a frame format and
 * a client library to carry them.
 *
 * <p>The origin allow-list mirrors {@link CorsConfig} and is a single origin rather than
 * {@code "*"} -- deliberately, even though this socket is public and carries nothing
 * private. A browser's same-origin policy does not apply to WebSockets at all, so an
 * unrestricted handshake lets any page on the internet open a connection against this
 * server and hold it. The data would be harmless; the connection slot would not be.
 *
 * <p>{@link EnableScheduling} is here rather than on the application class because the
 * feed's heartbeat is currently the only scheduled work in this app, and this is where
 * someone looking for it would expect it to be turned on.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final MarketWsHandler marketSocket;
    private final UserWsHandler userSocket;
    private final UserWsHandshakeInterceptor userHandshake;

    @Value("${exchange.cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    public WebSocketConfig(MarketWsHandler marketSocket, UserWsHandler userSocket,
                           UserWsHandshakeInterceptor userHandshake) {
        this.userSocket = userSocket;
        this.userHandshake = userHandshake;
        this.marketSocket = marketSocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(marketSocket, "/bff/ws/market").setAllowedOrigins(allowedOrigin);
        // A second endpoint rather than a channel on the first: this one requires a session
        // at the upgrade, routes by user instead of by symbol, and delivering a frame to the
        // wrong recipient here is a breach rather than noise. The handshake interceptor is
        // what enforces the first of those -- WebMvcConfig's interceptors never run for a
        // WebSocket upgrade, so the check has to be made again here.
        registry.addHandler(userSocket, "/bff/ws/user")
                .addInterceptors(userHandshake)
                .setAllowedOrigins(allowedOrigin);
    }
}
