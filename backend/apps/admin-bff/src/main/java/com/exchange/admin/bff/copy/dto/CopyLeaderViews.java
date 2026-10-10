package com.exchange.admin.bff.copy.dto;

import com.exchange.copy.leader.LeaderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The leaders page's shapes. */
public final class CopyLeaderViews {

    private CopyLeaderViews() {
    }

    /** A leader beside the customer who is one: staff see the account, followers never do.
     *  {@code profitShareBps} is this leader's own rate, null while they are on the exchange's. */
    public record Row(UUID id, Long userId, String email, String displayName, String bio, LeaderStatus status,
                      long followers, Long leadUserId, Instant appliedAt, Instant reviewedAt, String reviewReason,
                      Integer profitShareBps) {
    }

    /** A leader's own profit share, or null to put them back on the exchange's. */
    public record ProfitShare(@Min(0) @Max(10_000) Integer profitShareBps) {
    }

    public record Page_(List<Row> items, long total, int page, int size) {
    }

    /** Why, for the audit row and for whoever looks at this leader next. Never shown to the customer. */
    public record Reason(@NotBlank @Size(max = 255) String reason) {
    }
}
