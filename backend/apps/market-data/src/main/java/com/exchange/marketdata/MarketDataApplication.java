package com.exchange.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Consumes {@code exchange.spot.events.v1} and builds candles and L2 depth from it.
 *
 * <p>A separate app rather than more code in {@code spot-settlement} because of failure
 * domains, not size. Settlement is the sole writer of money state and aborts its whole batch on
 * any failure, so folding candle-building in would let a MongoDB hiccup stop balances settling.
 * A missing candle catches up on restart; a missing balance mutation is money.
 *
 * <p>Everything here is a projection -- drop the collections and replay the topic, since
 * {@code spot_trades} in MySQL remains the record of what happened. So it resumes from its
 * committed offset like any consumer, with none of the snapshot machinery the engine needs.
 *
 * <p><b>Run exactly one instance.</b> Scaling out was never useful (listener concurrency already
 * equals the partition count), but since {@link com.exchange.marketdata.ingest.DepthWriter}
 * holds each book in memory it is now a correctness requirement -- see that class for how a
 * second writer is made to fail loudly.
 *
 * <p>No HTTP for its actual job; {@code /actuator/health} is a liveness probe's side door (see
 * application.yml's {@code server.port} comment).
 */
// The stream package is the WebSocket fan-out web-bff and spot-open-api register; this app writes
// the projections it reads and holds no sockets, so its beans (which need a MarketFanout and an
// app's ProtocolErrors) must not be picked up here.
// Spelled out rather than @SpringBootApplication, which carries its own @ComponentScan and
// cannot take an exclusion.
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.exchange.marketdata", "com.exchange.core"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.exchange\\.marketdata\\.stream\\..*"))
public class MarketDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
