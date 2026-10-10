package com.exchange.spot.openapi.account.dto;

import com.exchange.fee.FeeQueryService.UserFeeSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Open-api's own copy of web-bff's view of the same thing: a contract must not move when a UI
 *  view does. Basis points are the exact value; the percentage is a rendering of it. A negative
 *  rate is a rebate. Tier fields are absent for a negotiated ({@code custom}) rate. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeeRatesView(int makerBps, int takerBps, String makerPercent, String takerPercent,
                           Integer tierLevel, String tierName, boolean custom) {

    private static final BigDecimal BPS_PER_PERCENT = BigDecimal.valueOf(100);

    public static FeeRatesView of(UserFeeSummary summary) {
        return new FeeRatesView(summary.rates().makerBps(), summary.rates().takerBps(),
                percent(summary.rates().makerBps()), percent(summary.rates().takerBps()),
                summary.tierLevel(), summary.tierName(), summary.custom());
    }

    private static String percent(int bps) {
        return BigDecimal.valueOf(bps).divide(BPS_PER_PERCENT, 4, RoundingMode.UNNECESSARY).toPlainString();
    }
}
