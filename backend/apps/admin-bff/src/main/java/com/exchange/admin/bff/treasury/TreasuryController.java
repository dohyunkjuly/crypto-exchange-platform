package com.exchange.admin.bff.treasury;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.treasury.dto.TreasuryView;
import com.exchange.admin.bff.treasury.dto.TreasuryView.Move;
import com.exchange.admin.bff.treasury.dto.TreasuryView.MoveFees;
import com.exchange.admin.bff.treasury.dto.TreasuryView.PayOut;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.exchange.wallet.treasury.TreasuryQueryService;
import com.exchange.common.money.Money;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.treasury.TreasuryQueryService.AssetHoldings;
import com.exchange.wallet.treasury.TreasuryQueryService.DayFlow;
import com.exchange.wallet.treasury.TreasuryQueryService.FeeFlow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the exchange holds and what trading has earned (docs/reference/treasury.md). Reading is
 * unannotated, so any admin may see it: a VIEWER who cannot read the balance sheet has nothing to
 * view. Moving money is an OPERATOR's, behind a fresh code, like everything else that moves money.
 *
 * <p>Windows are UTC days, and the boundaries are the ones the ledger is stamped in. An
 * operator reading this in another timezone sees a UTC day, which is the only definition that
 * agrees with the hourly summaries next to it.
 */
@RestController
@RequestMapping("/admin/treasury")
public class TreasuryController {

    private final TreasuryQueryService treasury;
    private final HouseAccounts house;
    private final AssetService assets;
    private final TreasuryMoves moves;

    public TreasuryController(TreasuryQueryService treasury, HouseAccounts house, AssetService assets,
                              TreasuryMoves moves) {
        this.treasury = treasury;
        this.house = house;
        this.assets = assets;
        this.moves = moves;
    }

    @PostMapping("/fee-moves")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public TreasuryView moveFees(@Valid @RequestBody MoveFees body, @CurrentAdmin Authenticated who,
                                 HttpServletRequest http) {
        moves.moveFees(who.admin().getId(), body.id(), body.asset(), body.amount(), body.reason().trim(),
                http.getRemoteAddr());
        return get();
    }

    @PostMapping("/payouts")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public TreasuryView payOut(@Valid @RequestBody PayOut body, @CurrentAdmin Authenticated who,
                               HttpServletRequest http) {
        moves.payOut(who.admin().getId(), body.id(), body.customer(), body.asset(), body.amount(),
                body.reason().trim(), http.getRemoteAddr());
        return get();
    }

    @GetMapping("/moves")
    public List<Move> history() {
        return moves.history();
    }

    @GetMapping
    public TreasuryView get() {
        Instant now = Instant.now();
        List<AssetHoldings> holdings = treasury.holdings(house.feeAccountId(), house.treasuryAccountId());

        Map<String, Map<Long, FeeFlow>> byPeriod = new LinkedHashMap<>();
        windows(now).forEach((name, from) -> byPeriod.put(name, byAsset(treasury.feeFlowSince(from, now))));

        List<TreasuryView.AssetLine> lines = new ArrayList<>();
        for (AssetHoldings holding : holdings) {
            Map<String, TreasuryView.Flow> periods = new LinkedHashMap<>();
            byPeriod.forEach((name, flows) -> periods.put(name,
                    flows.containsKey(holding.assetId())
                            ? TreasuryView.Flow.of(flows.get(holding.assetId()))
                            : TreasuryView.Flow.zero()));
            lines.add(TreasuryView.line(holding, periods));
        }
        return new TreasuryView(lines, treasury.lastSweptAt(house.feeAccountId()).orElse(null));
    }

    /** One day's fees and rebates, as decimal strings. */
    public record Day(LocalDate day, String fees, String rebates) {
    }

    /**
     * One asset's fee flow per UTC day, oldest first, for the chart. Capped at 90 days: the
     * chart answers "is this normal", and past a quarter that is a report, not a glance.
     */
    @GetMapping("/revenue")
    public List<Day> revenue(@RequestParam String asset, @RequestParam(defaultValue = "30") int days) {
        Asset found = assets.getBySymbol(asset);
        List<Day> series = new ArrayList<>();
        for (DayFlow flow : treasury.dailyFeeFlow(found.getId(), Math.min(Math.max(days, 1), 90), Instant.now())) {
            series.add(new Day(flow.day(), Money.toDecimal(flow.fees()), Money.toDecimal(flow.rebates())));
        }
        return series;
    }

    /** Where each window starts, UTC. "all" is the epoch: everything the ledger holds. */
    private static Map<String, Instant> windows(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Map<String, Instant> windows = new LinkedHashMap<>();
        windows.put("today", today.atStartOfDay(ZoneOffset.UTC).toInstant());
        windows.put("week", today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant());
        windows.put("month", today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        windows.put("all", Instant.EPOCH.truncatedTo(ChronoUnit.DAYS));
        return windows;
    }

    private static Map<Long, FeeFlow> byAsset(List<FeeFlow> flows) {
        Map<Long, FeeFlow> byAsset = new LinkedHashMap<>();
        flows.forEach(flow -> byAsset.put(flow.assetId(), flow));
        return byAsset;
    }
}
