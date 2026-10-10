package com.exchange.bff.common.config;

import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.marketdata.stream.ProtocolErrors;
import com.exchange.marketdata.stream.SocketLimits;
import com.exchange.marketdata.stream.WsChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This app's market socket: the shared handler with this app's guards and codes. Its own class
 * rather than {@code WebSocketConfig}, which depends on the handler -- a bean method there would
 * be a cycle.
 *
 * <p>Fifty subscriptions is a watchlist's worth of channel-and-symbol pairs (a trading page uses
 * two per market, so the number is deliberately above the twenty symbols this was before channels
 * existed). The send buffer drops a stalled browser rather than holding up the Kafka thread
 * fanning out to everyone else.
 */
@Configuration
public class MarketSocketConfig {

    @Bean
    public SocketLimits socketLimits() {
        return new SocketLimits(50, 5_000, 256 * 1024);
    }

    @Bean
    public MarketWsHandler marketWsHandler(ObjectMapper json, ProtocolErrors errors, SocketLimits limits,
                                           List<WsChannel> channels) {
        return new MarketWsHandler(json, errors, limits, channels);
    }
}
