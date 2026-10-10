package com.exchange.admin.bff.kyc.dto;

import com.exchange.admin.bff.users.dto.UserViews.Summary;
import com.exchange.user.identity.User;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.UserKycProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The KYC queue's shapes. */
public final class KycViews {

    private KycViews() {
    }

    /** A submission beside the account it belongs to. Names and dates are what was typed. */
    public record Submission(Summary user, String status, String level, String legalFirstName, String legalLastName,
                             LocalDate dateOfBirth, String nationality, String residenceCountry,
                             Instant submittedAt, Instant reviewedAt, Instant expiresAt, String rejectionReason) {

        public static Submission of(UserKycProfile p, User u) {
            return new Submission(Summary.of(u), p.getStatus().name(), p.getLevel().name(), p.getLegalFirstName(),
                    p.getLegalLastName(), p.getDateOfBirth(), p.getNationality(), p.getResidenceCountry(),
                    p.getSubmittedAt(), p.getReviewedAt(), p.getExpiresAt(), p.getRejectionReason());
        }
    }

    public record Queue(List<Submission> items, long total, int page, int size) {
    }

    /** @param expiresAt when re-verification falls due; null for a level that does not lapse */
    public record Approve(@NotNull KycLevel level, Instant expiresAt) {
    }

    public record Reject(@NotBlank @Size(max = 500) String reason) {
    }
}
