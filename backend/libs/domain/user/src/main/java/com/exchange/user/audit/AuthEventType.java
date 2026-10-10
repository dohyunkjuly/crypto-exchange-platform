package com.exchange.user.audit;

/**
 * Every security-relevant thing that can happen to a user account.
 *
 * <p>Add freely — there is deliberately no CHECK constraint on the column, so a new
 * value here needs no migration. Never rename or remove one: the name is what is stored
 * on disk, and historical rows still carry it.
 */
public enum AuthEventType {
    REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGOUT,
    SESSION_REVOKED,

    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,

    EMAIL_VERIFICATION_SENT,
    EMAIL_VERIFIED,
    EMAIL_CHANGED,

    /** A sign-in provider was attached to the account. A credential was added, so it
     *  leaves a trace of its own -- the REGISTERED event on a Google-created account says
     *  the account appeared, not which identity now opens it. */
    EXTERNAL_IDENTITY_LINKED,
    EXTERNAL_IDENTITY_UNLINKED,

    MFA_ENABLED,
    MFA_DISABLED,
    MFA_CHALLENGE_SUCCEEDED,
    MFA_CHALLENGE_FAILED,
    /** A fresh set of recovery codes was issued, invalidating the previous set. Worth its
     *  own value: it is the one MFA operation that changes a credential without changing
     *  whether 2FA is on, so it would otherwise leave no trace at all. */
    MFA_RECOVERY_CODES_REISSUED,

    /** Submitted details for verification. Says nothing about the outcome. */
    KYC_SUBMITTED,
    /** Verification granted, at a level. The level and expiry are in {@code metadata} --
     *  "approved" without saying to what is not an answer an auditor can use. */
    KYC_APPROVED,
    /** Verification refused. The reason is in {@code failure_reason}. */
    KYC_REJECTED,

    API_KEY_CREATED,
    API_KEY_UPDATED,
    API_KEY_REVOKED,

    WITHDRAWAL_ADDRESS_ADDED,
    WITHDRAWAL_ADDRESS_REMOVED,

    ACCOUNT_SUSPENDED,
    ACCOUNT_REACTIVATED,
    ACCOUNT_CLOSED,

    // Recorded on the master, with the sub's id in the metadata.
    SUB_ACCOUNT_CREATED,
    SUB_ACCOUNT_FROZEN,
    SUB_ACCOUNT_UNFROZEN
}
