package com.exchange.bff.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import com.exchange.wallet.treasury.FeeRollupService;
import com.exchange.wallet.treasury.FeeSweepService;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sweep and its hourly summaries, against a real database (docs/reference/treasury.md).
 *
 * <p>The unit tests cover the arithmetic with the repositories mocked. What only a database
 * can answer is whether the summary of an hour actually equals the ledger for that hour, and
 * whether the two halves of {@code earned} meet exactly at their boundary.
 *
 * <p>On an asset and a fee account of its own. The database is shared with whatever a
 * developer has traded and with a running settlement app that sweeps every minute, so a test
 * on USDT and {@code fees@exchange} would read their history as its own, and cleaning up after
 * itself would delete real summaries and real ledger rows.
 */
@SpringBootTest(properties = {
        "API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class FeeSweepIT {

    /** Far enough back that the hours are closed, and outside any other test's data. */
    private static final Instant HOUR = Instant.now().minus(5, ChronoUnit.HOURS)
            .truncatedTo(ChronoUnit.HOURS);

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("redis", 6379);
        requirePort("kafka", 9092);
        requirePort("mongodb", 27018);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }

    @Autowired
    private FeeRollupService rollup;

    @Autowired
    private FeeSweepService sweeps;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private JdbcTemplate jdbc;

    // Neither is a real account: balances and the ledger carry a plain user id, no foreign key.
    private final long trader = System.nanoTime();
    private final long feeAccountId = trader + 1;
    private Long assetId;

    /** By asset, whoever holds it: the running settlement app may have swept it to the real fee account. */
    @AfterEach
    void removeSeededData() {
        if (assetId == null) {
            return;
        }
        jdbc.update("DELETE FROM wallet_ledger_entries WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM wallet_balances WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM wallet_fee_hourly_totals WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM wallet_assets WHERE id = ?", assetId);
    }

    @Test
    @DisplayName("an hour's summary equals the ledger for that hour, and the sweep credits "
            + "summarised and live flow exactly once each")
    void summariesAndLiveFlowMeetAtTheBoundary() {
        setUp();
        // Two fees and a rebate inside the hour being summarised: 300 - 50.
        fee(120_00000000L, HOUR.plusSeconds(60));
        fee(180_00000000L, HOUR.plusSeconds(120));
        rebate(50_00000000L, HOUR.plusSeconds(180));
        // One in the hour after it, and one since: both belong to the live half, and an hour
        // that reaches past its own end would count the first of them twice.
        fee(80_00000000L, HOUR.plus(1, ChronoUnit.HOURS).plusSeconds(60));
        fee(40_00000000L, Instant.now().minusSeconds(30));

        rollup.rollUp(HOUR);

        Long summarised = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM wallet_fee_hourly_totals "
                        + "WHERE period_start = ? AND asset_id = ?", Long.class,
                java.sql.Timestamp.from(HOUR), assetId);
        Long fromLedger = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM wallet_ledger_entries "
                        + "WHERE asset_id = ? AND entry_type IN ('FEE', 'REBATE') "
                        + "AND created_at >= ? AND created_at < ?", Long.class,
                assetId, java.sql.Timestamp.from(HOUR),
                java.sql.Timestamp.from(HOUR.plus(1, ChronoUnit.HOURS)));
        assertThat(summarised).as("the summary is the hour, not an approximation of it")
                .isEqualTo(fromLedger);

        // The hour is summarised, the last fee is not: both must reach the account, once.
        assertThat(sweeps.earned(assetId, Instant.now())).isEqualTo(370_00000000L);
        assertThat(sweeps.sweep(feeAccountId, assetId, Instant.now())).isEqualTo(370_00000000L);
        assertThat(balances.findByUserIdAndAssetId(feeAccountId, assetId).orElseThrow().getTotal())
                .isEqualTo(370_00000000L);

        // Run again: the account is square, so nothing moves and no second entry is written.
        assertThat(sweeps.sweep(feeAccountId, assetId, Instant.now())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger_entries "
                + "WHERE user_id = ? AND asset_id = ? AND entry_type = 'FEE_SWEEP'",
                Integer.class, feeAccountId, assetId)).isEqualTo(1);
    }

    @Test
    @DisplayName("re-running a summarised hour leaves it as it was")
    void rollingUpTwiceIsTheSameAsOnce() {
        setUp();
        fee(42_00000000L, HOUR.plusSeconds(60));

        rollup.rollUp(HOUR);
        rollup.rollUp(HOUR);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_fee_hourly_totals "
                        + "WHERE period_start = ? AND asset_id = ?", Integer.class,
                java.sql.Timestamp.from(HOUR), assetId)).isEqualTo(1);
        assertThat(sweeps.earned(assetId, Instant.now())).isEqualTo(42_00000000L);
    }

    @Test
    @DisplayName("reconciliation catches a summarised hour that no longer matches the ledger")
    void aCorruptedSummaryIsCaught() {
        setUp();
        fee(100_00000000L, HOUR.plusSeconds(60));
        rollup.rollUp(HOUR);
        sweeps.sweep(feeAccountId, assetId, Instant.now());

        assertThat(sweeps.verify(feeAccountId, Instant.now()))
                .filteredOn(c -> c.assetId().equals(assetId))
                .allMatch(FeeSweepService.FeeCheck::ok);

        // Corrupt the hour as a botched backfill or a lost row would. The sweep would now
        // believe 60 was earned; only a check that reads the ledger itself can tell.
        jdbc.update("UPDATE wallet_fee_hourly_totals SET amount = ? "
                + "WHERE period_start = ? AND asset_id = ? AND entry_type = 'FEE'",
                -60_00000000L, java.sql.Timestamp.from(HOUR), assetId);

        FeeSweepService.FeeCheck check = sweeps.verify(feeAccountId, Instant.now()).stream()
                .filter(c -> c.assetId().equals(assetId)).findFirst().orElseThrow();
        assertThat(check.summariesAgree()).isFalse();
        assertThat(check.viaSummaries()).isEqualTo(60_00000000L);
        assertThat(check.fromLedger()).isEqualTo(100_00000000L);
        // The account is ahead of what the summaries claim and square with the ledger: it is
        // the summary that is wrong, and the money is not.
        assertThat(check.withinWhatWasEarned()).isTrue();
    }

    private void setUp() {
        // Which hours are summarised is one mark for every asset. If real fees have been
        // summarised into this test's hours, its own rows there would be skipped as already done.
        java.sql.Timestamp newest = jdbc.queryForObject(
                "SELECT MAX(period_start) FROM wallet_fee_hourly_totals", java.sql.Timestamp.class);
        Assumptions.assumeTrue(newest == null || newest.toInstant().isBefore(HOUR),
                "fees were summarised within the last five hours -- stop whatever is trading and run again");
        assetId = assets.save(new Asset("FS" + trader % 1_000_000_000L, "fee sweep test asset",
                AssetType.CRYPTO, 8)).getId();
    }

    /** A fee as settlement writes one: a debit, so a negative ledger amount. */
    private void fee(long amount, Instant at) {
        entry(LedgerEntryType.FEE, -amount, at);
    }

    private void rebate(long amount, Instant at) {
        entry(LedgerEntryType.REBATE, amount, at);
    }

    private void entry(LedgerEntryType type, long signedAmount, Instant at) {
        jdbc.update("INSERT INTO wallet_ledger_entries (id, user_id, asset_id, entry_type, amount, "
                        + "balance_after, reference_type, reference_id, idempotency_key, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, 'TRADE', ?, ?, ?)",
                uuidBytes(Ulid.randomUuid()), trader, assetId, type.name(), signedAmount,
                "fee-it", "fee-it:" + UUID.randomUUID(), java.sql.Timestamp.from(at));
    }

    private static byte[] uuidBytes(UUID id) {
        byte[] bytes = new byte[16];
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
