package com.exchange.spot.openapi.ws;

import com.exchange.marketdata.stream.ProtocolErrors;
import com.exchange.marketdata.stream.SocketLimits;
import com.exchange.marketdata.stream.WsChannel;
import com.exchange.spot.openapi.common.security.ApiKeyCache;
import com.exchange.spot.openapi.common.security.SignedRequestAuthenticator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The handler and its limits as beans, apart from {@code WebSocketConfig}, which depends on the
 *  handler (a bean method there would be a cycle). Fifty subscriptions, the private stream counting
 *  as one; a 256 KB send buffer, past which a slow bot is dropped rather than holding up the fan-out. */
@Configuration
public class SocketConfig {

    @Bean
    public SocketLimits socketLimits() {
        return new SocketLimits(50, 5_000, 256 * 1024);
    }

    /** Also the {@code UserFanout} the shared private consumers deliver through. */
    @Bean
    public OpenApiWsHandler openApiWsHandler(ObjectMapper json, ProtocolErrors errors, SocketLimits limits,
                                             List<WsChannel> channels, SignedRequestAuthenticator authenticator,
                                             ApiKeyCache keys, Clock clock,
                                             @Value("${exchange.rate-limit.ws-connections-per-ip}")
                                             int connectionsPerIp) {
        return new OpenApiWsHandler(json, errors, limits, channels, authenticator, keys, clock, connectionsPerIp);
    }
}
