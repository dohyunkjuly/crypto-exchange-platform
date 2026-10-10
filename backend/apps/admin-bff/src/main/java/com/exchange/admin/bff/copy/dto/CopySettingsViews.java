package com.exchange.admin.bff.copy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** The copy settings page's shapes. Amounts are in the copy asset, as decimal strings. */
public final class CopySettingsViews {

    private static final String AMOUNT = "\\d{1,12}(\\.\\d{1,8})?";

    private CopySettingsViews() {
    }

    public record Settings(int profitShareBps, int failureLimit, int maxFollowersPerLeader,
                           int maxCopiesPerUser, String minFixedRatioAmount,
                           String minFixedAmount, String leadMinBalance, String leadMaxBalance, String closeSellThreshold,
                           Instant updatedAt) {
    }

    /** Every setting, as the form holds them: the page saves the whole row, not a field. */
    public record SettingsBody(@Min(0) @Max(10_000) int profitShareBps,
                               @Min(1) @Max(1_000) int failureLimit, @Min(1) @Max(1_000_000) int maxFollowersPerLeader,
                               @Min(1) @Max(1_000) int maxCopiesPerUser,
                               @NotBlank @Pattern(regexp = AMOUNT) String minFixedRatioAmount,
                               @NotBlank @Pattern(regexp = AMOUNT) String minFixedAmount,
                               @NotBlank @Pattern(regexp = AMOUNT) String leadMinBalance,
                               @NotBlank @Pattern(regexp = AMOUNT) String leadMaxBalance,
                               @NotBlank @Pattern(regexp = AMOUNT) String closeSellThreshold) {
    }
}
