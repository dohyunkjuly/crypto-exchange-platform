package com.exchange.admin.bff.treasury.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import com.exchange.common.money.Money;
import com.exchange.wallet.treasury.TreasuryQueryService.AssetHoldings;
import com.exchange.wallet.treasury.TreasuryQueryService.FeeFlow;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The treasury screen in one response. Amounts are decimal strings, never JSON numbers
 * ({@link Money}), and never valued in anything: a coin is reported as the coin.
 *
 * @param sweptAt when fee revenue last reached the account, or null if it never has. The screen
 *                shows this whatever it says -- a sweeper that stopped is invisible otherwise
 *                (docs/reference/treasury.md).
 */
public record TreasuryView(List<AssetLine> assets, Instant sweptAt) {

    /** Fees on to the treasury. {@code id} is the caller's, so a retried request is the same move. */
    public record MoveFees(@NotNull UUID id,
                           @NotBlank String asset,
                           @NotBlank String amount,
                           @NotBlank @Size(max = 255) String reason) {
    }

    /** Treasury to a customer, named by id or email. */
    public record PayOut(@NotNull UUID id,
                         @NotBlank String customer,
                         @NotBlank String asset,
                         @NotBlank String amount,
                         @NotBlank @Size(max = 255) String reason) {
    }

    /** One move, as its audit row recorded it. {@code to} is null for fees moved to the treasury. */
    public record Move(Instant at, String kind, String admin, String asset, String amount, String to, String reason) {
    }

    /**
     * @param customerTotal what the books say customers hold: a liability, not reserves, and
     *                      never including the exchange's own accounts
     * @param periods fee flow by window -- "today", "week", "month", "all" -- each the fees
     *                earned and the rebates paid, as positive amounts
     */
    public record AssetLine(String symbol, String name, int displayDecimals,
                            String customerAvailable, String customerLocked, String customerTotal,
                            long holders, String feeAccount, String treasuryAccount,
                            Map<String, Flow> periods) {
    }

    public record Flow(String fees, String rebates, String net) {

        public static Flow of(FeeFlow flow) {
            return new Flow(Money.toDecimal(flow.fees()), Money.toDecimal(flow.rebates()),
                    Money.toDecimal(flow.net()));
        }

        public static Flow zero() {
            return new Flow("0", "0", "0");
        }
    }

    public static AssetLine line(AssetHoldings h, Map<String, Flow> periods) {
        return new AssetLine(h.symbol(), h.name(), h.displayDecimals(),
                Money.toDecimal(h.customerAvailable()), Money.toDecimal(h.customerLocked()),
                Money.toDecimal(h.customerTotal()), h.holders(),
                Money.toDecimal(h.feeAccount()), Money.toDecimal(h.treasuryAccount()), periods);
    }
}
