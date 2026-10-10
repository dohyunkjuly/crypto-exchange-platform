package com.exchange.bff.kyc.dto;

import com.exchange.user.kyc.UserKycProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Where a user's verification stands.
 *
 * <p>Carries the status and the level separately because they are different axes: someone
 * can hold {@code BASIC} (and be trading on it) while an upgrade to {@code INTERMEDIATE}
 * sits at {@code IN_REVIEW}. Collapsing them into one field is what makes a UI unable to
 * say "you can trade, and your upgrade is still being looked at".
 *
 * <p>Deliberately returns none of the submitted personal data -- that is {@link KycDetailsView},
 * fetched only where it is shown. Echoing a date of birth and legal name out of the most sensitive
 * table in the schema on every status poll is a needless place for it to leak.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KycStatusView(String status, String level, Instant submittedAt,
                            Instant reviewedAt, Instant expiresAt, String rejectionReason) {

    public static KycStatusView of(UserKycProfile profile) {
        return new KycStatusView(profile.getStatus().name(), profile.getLevel().name(),
                profile.getSubmittedAt(), profile.getReviewedAt(), profile.getExpiresAt(),
                profile.getRejectionReason());
    }
}
