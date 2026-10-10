package com.exchange.bff.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * What a user tells us about themselves.
 *
 * <p><b>No document, no document number, no selfie.</b> Those go straight from the
 * browser to the verification provider's SDK and never touch this API -- see V1__user.sql, and
 * {@code KycController} for why that split is not negotiable.
 *
 * <p>These fields are the ones the platform itself has to reason about later: country for
 * geo and sanctions rules, date of birth for age checks, legal name for matching against
 * withdrawal destinations.
 */
public record SubmitKycRequest(
        @NotBlank String legalFirstName,
        @NotBlank String legalLastName,
        @NotNull @Past LocalDate dateOfBirth,
        /* ISO 3166-1 alpha-2, upper case -- the format every sanctions and geo rule
           downstream expects. Validated here rather than normalised, so a client sending
           "gb" is told rather than silently corrected. */
        @Pattern(regexp = "[A-Z]{2}") String nationality,
        @Pattern(regexp = "[A-Z]{2}") String residenceCountry) {
}
