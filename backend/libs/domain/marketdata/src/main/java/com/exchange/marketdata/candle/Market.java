package com.exchange.marketdata.candle;

/**
 * Which product a candle belongs to, and therefore which collection it lives in.
 *
 * <p>A symbol name is only unique <em>within</em> a product: {@code BTCUSDT} is both a spot
 * market and a perpetual, trading at different prices. Without separation both write the same
 * bucket and {@code CandleWriter}'s upsert merges them -- volume summed across two markets,
 * high and low across two unrelated price series, with no error to notice.
 *
 * <p>A collection per product rather than a shared one with this as a discriminator. At two or
 * three products that costs almost nothing (the per-collection overhead argument is about a
 * collection per <em>symbol</em>, which would be thousands) and it buys room to diverge --
 * futures candles will carry mark price and open interest, which in a shared collection would
 * be sparse fields on half the documents. It also bounds the blast radius. The trade-off: a
 * query spanning both products needs {@code $unionWith}, which is rare by nature.
 *
 * <p>Never rename a constant -- the name is in every document, and the code is part of both the
 * collection name and every candle's id.
 */
public enum Market {

    SPOT("spot"),
    /** Nothing writes these yet. The collection and the id format already account for
     *  them, so launching futures adds a consumer rather than a migration. */
    FUTURES("futures");

    private final String code;

    Market(String code) {
        this.code = code;
    }

    /** The short form used in a candle's id and in API responses. */
    public String code() {
        return code;
    }

    /** {@code spot_market_candles} / {@code futures_market_candles}. */
    public String candleCollection() {
        return code + "_market_candles";
    }

    /** {@code spot_market_trades} / {@code futures_market_trades}. The public tape -- one
     *  rolling window per symbol, distinct from {@code spot_trades} in MySQL, which is the
     *  permanent record and carries counterparties and fees this must never expose. */
    public String tapeCollection() {
        return code + "_market_trades";
    }

    /** {@code spot_market_depth} / {@code futures_market_depth}. Its own collection rather
     *  than a field on the candle documents: a book is one current row per symbol that is
     *  rewritten constantly, candles are millions of immutable rows that are only appended
     *  to. Nothing about the two read or write alike. */
    public String depthCollection() {
        return code + "_market_depth";
    }
}
