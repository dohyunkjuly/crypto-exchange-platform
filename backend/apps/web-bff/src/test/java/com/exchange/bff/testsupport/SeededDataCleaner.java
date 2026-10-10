package com.exchange.bff.testsupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletes everything a test seeded, so runs do not accumulate in the shared dev database.
 *
 * <p>A near-copy of {@code com.exchange.spot.settlement.testsupport.SeededDataCleaner},
 * plus the user cleanup below. Deliberately duplicated rather than shared: it is
 * test-only code in two different apps, and the alternative is a {@code testFixtures}
 * source set plus the wiring for another module to consume it -- the same trade
 * {@code ConsumerLivenessHealthIndicator} already resolved the same way in main code.
 *
 * <p>The extra work here is real users. The settlement tests invent a user id from {@code
 * System.nanoTime()} and never insert a {@code user_users} row, because {@code
 * spot_orders.user_id} is deliberately not a foreign key (AGENTS.md). This app's
 * tests register through {@code /bff/auth/register}, which creates an actual user with
 * sessions and auth events hanging off it by foreign key -- so those come out too, children
 * first.
 */
public class SeededDataCleaner {

    private final JdbcTemplate jdbc;

    public SeededDataCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Safe on ids that were never used -- every statement simply matches nothing. */
    public void purgeMarket(Long symbolId, Long baseAssetId, Long quoteAssetId) {
        if (symbolId == null) {
            return;
        }
        // spot_order_fill_log -> spot_orders is the one foreign key left on the fill path
        // (the fill-path tables have none), so it goes before the orders it points at.
        jdbc.update("""
                DELETE f FROM spot_order_fill_log f
                    JOIN spot_orders o ON o.id = f.order_id
                 WHERE o.symbol_id = ?
                """, symbolId);
        jdbc.update("DELETE FROM spot_orders WHERE symbol_id = ?", symbolId);
        jdbc.update("DELETE FROM spot_trades WHERE symbol_id = ?", symbolId);

        // wallet_balances.asset_id -> wallet_assets is a real foreign key, so balances
        // must go before the assets they reference.
        jdbc.update("DELETE FROM wallet_ledger_entries WHERE asset_id IN (?, ?)",
                baseAssetId, quoteAssetId);
        jdbc.update("DELETE FROM wallet_balances WHERE asset_id IN (?, ?)",
                baseAssetId, quoteAssetId);

        jdbc.update("DELETE FROM spot_symbols WHERE id = ?", symbolId);
        jdbc.update("DELETE FROM wallet_assets WHERE id IN (?, ?)", baseAssetId, quoteAssetId);
    }

    /** Every table with a foreign key to {@code user_users}, then the users themselves. */
    public void purgeUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        // A sub-account is a user of its own, with its own keys, balances and ledger: it goes
        // the same way as its master, and before it (the parent key).
        List<Long> all = new ArrayList<>(userIds);
        all.addAll(0, jdbc.queryForList("SELECT id FROM user_users WHERE parent_user_id IN ("
                + placeholders(userIds.size()) + ")", Long.class, userIds.toArray()));
        String ids = placeholders(all.size());
        Object[] args = all.toArray();
        // Every table with a user_id pointing at user_users. This list is maintained by
        // hand and MySQL will tell you when it is wrong -- a missing entry surfaces as a
        // foreign-key violation on the delete below, not as silent leftover rows. Add to
        // it when you add a user-owned table.
        for (String table : List.of("user_sessions", "user_auth_events", "user_api_keys",
                "user_mfa_methods", "user_mfa_recovery_codes", "user_kyc_profiles",
                "user_email_verification_tokens", "fee_user_overrides",
                "wallet_balances", "wallet_ledger_entries")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id IN (" + ids + ")", args);
        }
        // Two sides, neither called user_id; and no foreign key, so nothing would complain.
        jdbc.update("DELETE FROM wallet_transfers WHERE from_user_id IN (" + ids + ") OR to_user_id IN ("
                + ids + ")", concat(args, args));
        // The subs first, or the master's delete is refused: one DELETE checks the key row by row.
        jdbc.update("DELETE FROM user_users WHERE id IN (" + ids + ") AND parent_user_id IS NOT NULL", args);
        jdbc.update("DELETE FROM user_users WHERE id IN (" + ids + ")", args);
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] both = new Object[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
