package com.exchange.bff.fee.dto;

import com.exchange.fee.FeeQueryService.UserFeeSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What this user pays to trade.
 *
 * <p>Two rates, not one, because which applies is not known until the order executes: a
 * limit order that rests pays maker, one that crosses pays taker, and a market order always
 * takes. An order form shows both and the fill settles it.
 *
 * <p><b>Rates, not amounts.</b> The estimate a form displays -- "≈ 10 USDT" -- is this
 * multiplied by whatever the user has typed, which changes on every keystroke and belongs in
 * the browser. The server answers the part that is a property of the account rather than of
 * the order, and the client already knows from {@code /bff/symbols} which asset a fee lands
 * in: the buyer is charged in the base asset, the seller in the quote.
 *
 * <p>Basis points are carried alongside the percentages because they are the exact value --
 * an integer, the same one stamped onto the order and used to compute the fill. The
 * percentage is a rendering of it, and 1 bps is 0.01%.
 *
 * <p>A negative rate is a rebate, not an error: the exchange pays makers on some tiers, and
 * a client that renders {@code -0.0200%} as a charge has it exactly backwards.
 *
 * <p>{@code tierLevel} and {@code tierName} are absent when {@code custom} is true -- a
 * negotiated rate belongs to no published rung, and naming one would show a schedule that
 * does not apply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeeRatesView(int makerBps,
                           int takerBps,
                           String makerPercent,
                           String takerPercent,
                           Integer tierLevel,
                           String tierName,
                           boolean custom) {

    private static final BigDecimal BPS_PER_PERCENT = BigDecimal.valueOf(100);

    public static FeeRatesView of(UserFeeSummary summary) {
        return new FeeRatesView(summary.rates().makerBps(),
                summary.rates().takerBps(),
                percent(summary.rates().makerBps()),
                percent(summary.rates().takerBps()),
                summary.tierLevel(),
                summary.tierName(),
                summary.custom());
    }

    /** Four decimal places, which is what one basis point needs: 1 bps is 0.0100%. Fewer
     *  would round a rebate of -2 bps to -0.02% and a rate of 1 bps to 0.01%, both fine --
     *  but a venue quoting half-basis-point tiers would silently lose the half. */
    private static String percent(int bps) {
        return BigDecimal.valueOf(bps)
                .divide(BPS_PER_PERCENT, 4, RoundingMode.UNNECESSARY)
                .toPlainString();
    }
}
