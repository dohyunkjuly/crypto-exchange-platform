package com.exchange.user.kyc;

import com.exchange.user.identity.User;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.kyc.exception.KycAlreadyVerifiedException;
import java.time.LocalDate;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing that may change a user's verification level.
 *
 * <p>"Only" is the point. {@code user_kyc_profiles} is the record and
 * {@code user_users.kyc_level}/{@code kyc_verified_until} are the read model the trading
 * gate actually reads (V1__user.sql), and the two must move together or an account trades on a
 * level it no longer holds. Funnelling every change through one transactional method is
 * what makes that structural rather than a rule someone has to remember.
 *
 * <p>Deliberately small for now: enough to record an approval and a rejection. The review
 * workflow -- provider callbacks, the compliance queue, document handling -- is a separate
 * problem and does not belong in the path a trading gate reads.
 *
 * <p>Every change here is audited. A verification level is what decides whether an account
 * may trade and how much it may withdraw, so "who was granted what, when, and on whose
 * decision" is the first thing an auditor asks for -- and the profile row alone only ever
 * shows the current state, never how it got there.
 */
@Service
public class KycService {

    /** user_auth_events.failure_reason is VARCHAR(64); the untruncated reason is on the
     *  profile row. Truncating here rather than letting the insert fail keeps a rejection
     *  from being lost because someone wrote a long explanation. */
    private static final int MAX_FAILURE_REASON = 64;

    private final UserKycProfileRepository profiles;
    private final UserRepository users;
    private final UserAuthEventRepository authEvents;
    private final ApplicationEventPublisher events;

    public KycService(UserKycProfileRepository profiles, UserRepository users,
                      UserAuthEventRepository authEvents, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.users = users;
        this.authEvents = authEvents;
        this.events = events;
    }

    /**
     * Records what the user says about themselves and puts the profile in review.
     *
     * <p>Only the fields the platform itself reasons about -- country for geo and
     * sanctions rules, date of birth for age checks, legal name for matching against
     * withdrawal destinations. Documents and document numbers are deliberately not here
     * and never will be: the verification provider holds those under its own regime, and
     * a second copy would double the blast radius of a breach for no benefit. See V1__user.sql.
     *
     * <p>This grants nothing. The level stays where it was until something approves it --
     * a provider's webhook, or a reviewer on admin-bff.
     *
     * @throws EmailNotVerifiedException  the address is unconfirmed -- identity checks start
     *         from a mailbox we know the user holds, as every venue requires
     * @throws KycAlreadyVerifiedException re-submitting over an approved profile, which
     *         would otherwise silently reset a verified account to PENDING
     */
    @Transactional
    public void submit(Long userId, String legalFirstName, String legalLastName,
                       LocalDate dateOfBirth, String nationality, String residenceCountry,
                       String ipAddress) {
        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId));
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        UserKycProfile profile = profiles.findByUserId(userId)
                .orElseGet(() -> new UserKycProfile(userId));
        if (profile.isVerified(Instant.now())) {
            throw new KycAlreadyVerifiedException();
        }

        profile.setLegalFirstName(legalFirstName);
        profile.setLegalLastName(legalLastName);
        profile.setDateOfBirth(dateOfBirth);
        profile.setNationality(nationality);
        profile.setResidenceCountry(residenceCountry);
        profile.setStatus(KycStatus.PENDING);
        profile.setSubmittedAt(Instant.now());
        profile.setRejectionReason(null);
        profiles.save(profile);

        // The submitted values are deliberately NOT in the event. They are the most
        // sensitive fields in the schema, and an audit log is read far more widely than
        // the profile table -- recording that a submission happened is the useful part.
        record(userId, AuthEventType.KYC_SUBMITTED, AuthOutcome.SUCCESS, ipAddress, null, null);

        events.publishEvent(new KycSubmittedEvent(userId));
    }

    /**
     * No ip address on approve or reject.
     *
     * <p>Neither is a browser action: they arrive from a verification provider's webhook,
     * or from a reviewer on admin-bff, whose own audit row carries the address. Recording it here would put the
     * provider's IP in a column every other row uses for the user's, which is worse than
     * leaving it empty. Which provider decided is already on the profile.
     */
    private void record(Long userId, AuthEventType type, AuthOutcome outcome,
                        String ipAddress, String failureReason, String metadata) {
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(type)
                .outcome(outcome)
                .ipAddress(ipAddress)
                .failureReason(failureReason)
                .metadata(metadata)
                .build());
    }

    /**
     * Hand-built rather than serialised, matching {@code ApiKeyService.toJsonArray} -- this
     * module has no Jackson on its classpath.
     *
     * <p>Safe without escaping here specifically: both values are machine-generated, an
     * enum constant name and an ISO-8601 instant, neither of which can contain a quote or
     * a backslash. Anything user-supplied would need the escaping that method does.
     */
    private static String approvalMetadata(KycLevel level, Instant expiresAt) {
        return "{\"level\":\"" + level.name() + "\",\"expiresAt\":"
                + (expiresAt == null ? "null" : "\"" + expiresAt + "\"") + "}";
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_FAILURE_REASON) {
            return reason;
        }
        return reason.substring(0, MAX_FAILURE_REASON);
    }

    /** What the profile currently says, or an empty NOT_STARTED view for someone who has
     *  never submitted. Callers render this; nothing here decides what it unlocks -- that
     *  is {@code EligibilityService}. */
    @Transactional(readOnly = true)
    public UserKycProfile statusOf(Long userId) {
        return profiles.findByUserId(userId).orElseGet(() -> new UserKycProfile(userId));
    }

    /**
     * Records a completed verification and opens the account to what that level allows.
     *
     * @param expiresAt when re-verification falls due, or null if this level does not
     *                  lapse. Copied to the user row so the gate expires itself rather
     *                  than depending on a sweep job noticing.
     */
    @Transactional
    public void approve(Long userId, KycLevel level, Instant expiresAt) {
        UserKycProfile profile = profiles.findByUserId(userId)
                .orElseGet(() -> new UserKycProfile(userId));
        profile.setStatus(KycStatus.APPROVED);
        profile.setLevel(level);
        profile.setReviewedAt(Instant.now());
        profile.setExpiresAt(expiresAt);
        profile.setRejectionReason(null);
        profiles.save(profile);

        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId));
        user.setKycLevel(level);
        user.setKycVerifiedUntil(expiresAt);
        users.save(user);

        record(userId, AuthEventType.KYC_APPROVED, AuthOutcome.SUCCESS, null, null,
                approvalMetadata(level, expiresAt));
    }

    /**
     * Refuses a verification, and closes whatever it had opened.
     *
     * <p>The level drops to {@code NONE} rather than staying where it was: a rejection
     * that left the old level in place would be a rejection that changed nothing.
     */
    @Transactional
    public void reject(Long userId, String reason) {
        UserKycProfile profile = profiles.findByUserId(userId)
                .orElseGet(() -> new UserKycProfile(userId));
        profile.setStatus(KycStatus.REJECTED);
        profile.setLevel(KycLevel.NONE);
        profile.setReviewedAt(Instant.now());
        profile.setRejectionReason(reason);
        profiles.save(profile);

        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId));
        user.setKycLevel(KycLevel.NONE);
        user.setKycVerifiedUntil(null);
        users.save(user);

        record(userId, AuthEventType.KYC_REJECTED, AuthOutcome.FAILURE, null,
                truncate(reason), null);
    }
}
