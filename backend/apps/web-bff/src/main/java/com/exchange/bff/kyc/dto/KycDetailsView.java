package com.exchange.bff.kyc.dto;

import com.exchange.user.kyc.UserKycProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * What the user submitted about themselves, shown back to them on the Identification page.
 *
 * <p>Its own endpoint rather than fields on {@link KycStatusView}: the status is polled during
 * review and read by the dashboard, and the legal name and date of birth have no business
 * riding along on every one of those. Fetched only where they are displayed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KycDetailsView(String legalFirstName, String legalLastName, LocalDate dateOfBirth,
                             String nationality, String residenceCountry) {

    public static KycDetailsView of(UserKycProfile profile) {
        return new KycDetailsView(profile.getLegalFirstName(), profile.getLegalLastName(),
                profile.getDateOfBirth(), profile.getNationality(), profile.getResidenceCountry());
    }
}
