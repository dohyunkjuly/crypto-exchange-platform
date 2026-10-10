package com.exchange.user.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The most sensitive table in the schema. Holds only what the platform itself must reason
 * about — country for geo and sanctions rules, date of birth for age checks, legal name
 * for matching withdrawal destinations. Document images and numbers stay with the KYC
 * provider, referenced by {@code providerApplicantId}.
 */
@Entity
@Table(name = "user_kyc_profiles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserKycProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, unique = true)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KycStatus status = KycStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KycLevel level = KycLevel.NONE;

    @Column(length = 100)
    private String legalFirstName;

    @Column(length = 100)
    private String legalLastName;

    private LocalDate dateOfBirth;

    /** ISO 3166-1 alpha-2. */
    @Column(length = 2)
    private String nationality;

    @Column(length = 2)
    private String residenceCountry;

    @Column(length = 32)
    private String provider;

    @Column(length = 128)
    private String providerApplicantId;

    private Instant submittedAt;

    private Instant reviewedAt;

    /** KYC goes stale; most jurisdictions require periodic re-verification. */
    private Instant expiresAt;

    @Column(length = 255)
    private String rejectionReason;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public UserKycProfile(Long userId) {
        this.userId = userId;
    }

    public boolean isVerified(Instant now) {
        return status == KycStatus.APPROVED && (expiresAt == null || expiresAt.isAfter(now));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserKycProfile other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
