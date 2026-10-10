package com.exchange.spot.openapi.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the socket at {@code /v1/ws}. Any origin: this is a public API whose clients are
 * mostly not browsers and send no Origin at all; a browser page from anywhere may connect, and
 * the per-IP connection cap is what bounds the slots it can hold.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OpenApiWsHandler socket;

    public WebSocketConfig(OpenApiWsHandler socket) {
        this.socket = socket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/v1/ws").setAllowedOrigins("*");
    }
}
