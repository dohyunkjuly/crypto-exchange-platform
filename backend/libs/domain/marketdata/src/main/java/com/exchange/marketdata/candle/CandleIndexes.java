package com.exchange.marketdata.candle;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates each product's candle indexes at startup.
 *
 * <p>Explicit because Spring Data's {@code auto-index-creation} only knows how to index
 * the one collection an {@code @Document} names, and candles have one collection per
 * product chosen at call time ({@link Market#candleCollection()}). Left implicit, the
 * futures collection would come into existence the day futures launches with no indexes at
 * all -- every chart query a collection scan, and nothing to notice it but latency.
 *
 * <p>Index creation is idempotent: MongoDB treats creating an index that already exists
 * with the same definition as a no-op, so this runs on every boot without doing any work
 * after the first.
 *
 * <p>There is deliberately no TTL index. One used to live here, expiring candles by age.
 * Removing it from this class does <b>not</b> drop it from a collection that already has
 * one -- an existing index keeps deleting documents until an operator drops it by hand
 * ({@code db.spot_market_candles.dropIndex("ttl_candles_expires_at")}). Anyone bringing an
 * older deployment forward has to do that, and nothing here will warn them.
 */
@Component
public class CandleIndexes {

    private static final Logger log = LoggerFactory.getLogger(CandleIndexes.class);

    private final MongoTemplate mongo;

    public CandleIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        for (Market market : Market.values()) {
            String collection = market.candleCollection();

            // Every read -- a chart window, the newest N candles, the ticker's 24 hourly
            // buckets -- filters on symbol AND interval, then orders by openTime. One
            // compound index covers all of them; without it they are collection scans
            // (measured at 21,104 documents examined to return 0 before this existed).
            // No `market` key: within one product's collection it has a single value, so
            // it would only make every index entry bigger.
            mongo.indexOps(collection).createIndex(
                    new Index().on("symbol", org.springframework.data.domain.Sort.Direction.ASC)
                            .on("interval", org.springframework.data.domain.Sort.Direction.ASC)
                            .on("openTime", org.springframework.data.domain.Sort.Direction.DESC)
                            .named("idx_candles_symbol_interval_open_time"));


            log.info("candle indexes ready on {}", collection);
        }
    }
}
