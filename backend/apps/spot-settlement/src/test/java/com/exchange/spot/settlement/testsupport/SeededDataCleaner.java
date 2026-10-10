package com.exchange.spot.settlement.testsupport;

import com.exchange.spot.symbol.SpotSymbol;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletes everything a test seeded, so runs do not silently accumulate in the shared dev
 * database.
 *
 * <p>They did, for a long time: a survey of that database found 103,000 orders, 212,000
 * ledger entries and 196,000 balances, none of it from anything but test runs. That is
 * not merely untidy -- these benchmarks measure insert and lookup cost against tables
 * whose size was being set by however many times anyone had run them before, so the
 * numbers were drifting for a reason unrelated to any code change.
 *
 * <p>Everything a test creates hangs off three ids -- the symbol, and its two assets --
 * so those are enough to remove all of it without tracking individual rows. Order matters:
 * {@code spot_order_fill_log -> spot_orders} is the one foreign key left on the fill path
 * (the fill-path tables have none) and {@code wallet_balances.asset_id -> wallet_assets} is a real
 * one, so children go before parents.
 *
 * <p>Benchmarks use {@code System.nanoTime()} as a user id and never insert a matching
 * {@code user_users} row -- {@code spot_orders.user_id} is deliberately not a foreign key,
 * per AGENTS.md -- so there are no user rows here to clean up. web-bff's
 * {@code OrderLifecycleIT} registers real users through HTTP and carries its own near-copy
 * of this class that deletes them too. Deliberately duplicated rather than shared: it is
 * test-only code in two apps, and the alternative is a testFixtures source set plus the
 * wiring to consume it, which is the same trade {@code ConsumerLivenessHealthIndicator}
 * already resolved the same way.
 */
public final class SeededDataCleaner {

    private SeededDataCleaner() {
    }

    /** Safe to call on a symbol that was never used -- every statement simply matches
     *  nothing. Call it from a {@code finally} so a failing test still cleans up. */
    public static void purge(ApplicationContext ctx, SpotSymbol symbol) {
        if (symbol == null || symbol.getId() == null) {
            return;
        }
        purge(ctx.getBean(JdbcTemplate.class), symbol.getId(),
                symbol.getBaseAssetId(), symbol.getQuoteAssetId());
    }

    public static void purge(JdbcTemplate jdbc, Long symbolId, Long baseAssetId,
                             Long quoteAssetId) {
        jdbc.update("""
                DELETE f FROM spot_order_fill_log f
                    JOIN spot_orders o ON o.id = f.order_id
                 WHERE o.symbol_id = ?
                """, symbolId);
        jdbc.update("DELETE FROM spot_orders WHERE symbol_id = ?", symbolId);
        jdbc.update("DELETE FROM spot_trades WHERE symbol_id = ?", symbolId);

        jdbc.update("DELETE FROM wallet_ledger_entries WHERE asset_id IN (?, ?)",
                baseAssetId, quoteAssetId);
        jdbc.update("DELETE FROM wallet_balances WHERE asset_id IN (?, ?)",
                baseAssetId, quoteAssetId);

        jdbc.update("DELETE FROM spot_symbols WHERE id = ?", symbolId);
        jdbc.update("DELETE FROM wallet_assets WHERE id IN (?, ?)", baseAssetId, quoteAssetId);
    }
}
