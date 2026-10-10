package com.exchange.wallet.treasury;

import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the exchange holds, for the treasury screen (docs/reference/treasury.md).
 *
 * <p>Everything here is in native units and scaled by 1e8. Nothing is valued: what a coin is
 * worth today and what it earned are different questions, and one number cannot answer both.
 *
 * <p>Customer holdings are a liability -- what the books say is owed -- not reserves. Nothing
 * in this repo touches a blockchain.
 */
@Service
public class TreasuryQueryService {

    /** One asset's line on the screen. Amounts are signed longs, scaled by 1e8. */
    public record AssetHoldings(Long assetId, String symbol, String name, int displayDecimals,
                                boolean tradingEnabled, boolean depositEnabled, boolean withdrawalEnabled,
                                long customerAvailable, long customerLocked, long holders,
                                long feeAccount, long treasuryAccount) {

        public long customerTotal() {
            return customerAvailable + customerLocked;
        }
    }

    /** Fee flow over one window, per asset: what was earned and what was paid back out. */
    public record FeeFlow(Long assetId, long fees, long rebates) {

        public long net() {
            return fees - rebates;
        }
    }

    private final BalanceRepository balances;
    private final LedgerEntryRepository ledger;
    private final AssetRepository assets;

    public TreasuryQueryService(BalanceRepository balances, LedgerEntryRepository ledger,
                                AssetRepository assets) {
        this.balances = balances;
        this.ledger = ledger;
        this.assets = assets;
    }

    /**
     * One line per asset anyone holds, the exchange's own accounts shown separately from what
     * customers hold rather than summed into it.
     *
     * @param houseAccountIds the fee and treasury accounts, excluded from the customer totals
     */
    @Transactional(readOnly = true)
    public List<AssetHoldings> holdings(Long feeAccountId, Long treasuryAccountId) {
        List<Long> houseAccountIds = List.of(feeAccountId, treasuryAccountId);
        Map<Long, long[]> customer = new LinkedHashMap<>();
        for (Object[] row : balances.totalsByAssetExcluding(houseAccountIds)) {
            customer.put((Long) row[0], new long[] {
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue(), ((Number) row[3]).longValue()});
        }
        Map<Long, Long> fees = heldBy(feeAccountId);
        Map<Long, Long> treasury = heldBy(treasuryAccountId);

        List<AssetHoldings> lines = new ArrayList<>();
        for (Asset asset : assets.findAll()) {
            long[] held = customer.getOrDefault(asset.getId(), new long[] {0, 0, 0});
            long fee = fees.getOrDefault(asset.getId(), 0L);
            long house = treasury.getOrDefault(asset.getId(), 0L);
            // An asset nobody holds and that earned nothing is a row of zeros; it belongs on a
            // list of what is listed, not on one of what is held.
            if (held[0] == 0 && held[1] == 0 && held[2] == 0 && fee == 0 && house == 0) {
                continue;
            }
            lines.add(new AssetHoldings(asset.getId(), asset.getSymbol(), asset.getName(),
                    asset.getDisplayDecimals(), asset.isTradingEnabled(), asset.isDepositEnabled(),
                    asset.isWithdrawalEnabled(), held[0], held[1], held[2], fee, house));
        }
        lines.sort(Comparator.comparing(AssetHoldings::symbol));
        return lines;
    }

    /**
     * What customers paid in fees, and were paid in rebates, over {@code [from, now)}.
     *
     * <p>Read from the ledger rather than the hourly summaries: this is a screen, so a slow
     * answer is better than one that inherits whatever the summaries got wrong. Reported as
     * positive amounts -- a fee debits the trader, so it is stored negative.
     */
    @Transactional(readOnly = true)
    public List<FeeFlow> feeFlowSince(Instant from, Instant now) {
        Map<Long, long[]> byAsset = new LinkedHashMap<>();
        for (Object[] row : ledger.sumByAssetAndTypeBetween(FeeRollupService.FEE_FLOW, from, now)) {
            Long assetId = (Long) row[0];
            long amount = ((Number) row[2]).longValue();
            long[] pair = byAsset.computeIfAbsent(assetId, id -> new long[2]);
            if (row[1] == LedgerEntryType.FEE) {
                pair[0] = -amount;
            } else {
                pair[1] = amount;
            }
        }
        List<FeeFlow> flows = new ArrayList<>();
        byAsset.forEach((assetId, pair) -> flows.add(new FeeFlow(assetId, pair[0], pair[1])));
        return flows;
    }

    /** One UTC day of fee flow, as positive amounts; days with nothing are filled in as zero. */
    public record DayFlow(LocalDate day, long fees, long rebates) {
    }

    /**
     * The last {@code days} UTC days of one asset's fee flow, oldest first, every day present.
     * A missing day would read as a gap in the chart rather than as a day that earned nothing,
     * and those are different things.
     */
    @Transactional(readOnly = true)
    public List<DayFlow> dailyFeeFlow(Long assetId, int days, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(d, new long[2]);
        }
        List<String> types = FeeRollupService.FEE_FLOW.stream().map(Enum::name).toList();
        for (Object[] row : ledger.sumByDayAndType(types, assetId,
                first.atStartOfDay(ZoneOffset.UTC).toInstant(), now)) {
            LocalDate day = ((java.sql.Date) row[0]).toLocalDate();
            long amount = ((Number) row[2]).longValue();
            long[] pair = byDay.get(day);
            if (pair == null) {
                continue;
            }
            if (LedgerEntryType.FEE.name().equals(row[1])) {
                pair[0] = -amount;
            } else {
                pair[1] = amount;
            }
        }
        List<DayFlow> flows = new ArrayList<>();
        byDay.forEach((day, pair) -> flows.add(new DayFlow(day, pair[0], pair[1])));
        return flows;
    }

    /**
     * When the fee sweep last ran. Empty means it never has -- which is the same thing the
     * screen must show loudly, because a sweeper that stopped is otherwise invisible.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSweptAt(Long feeAccountId) {
        return ledger.newestOf(feeAccountId, LedgerEntryType.FEE_SWEEP);
    }

    private Map<Long, Long> heldBy(Long userId) {
        Map<Long, Long> held = new LinkedHashMap<>();
        for (Balance balance : balances.findByUserId(userId)) {
            held.put(balance.getAssetId(), balance.getTotal());
        }
        return held;
    }
}
