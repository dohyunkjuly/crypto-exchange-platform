package com.exchange.admin.bff.fees.dto;

import com.exchange.common.money.Money;
import com.exchange.fee.tier.FeeTier;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** The Fees page's shapes. */
public final class FeeViews {

    private FeeViews() {
    }

    /** Volume is a decimal string in the quote asset; rates are whole basis points. */
    public record Level(int level, String name, String min30dVolume, int makerFeeBps, int takerFeeBps,
                        Instant updatedAt) {

        public static Level of(FeeTier t) {
            return new Level(t.getLevel(), t.getName(), Money.toDecimal(t.getMin30dVolume()), t.getMakerFeeBps(),
                    t.getTakerFeeBps(), t.getUpdatedAt());
        }
    }

    public record LevelBody(@NotNull String name, @NotNull String min30dVolume, int makerFeeBps, int takerFeeBps) {
    }
}
