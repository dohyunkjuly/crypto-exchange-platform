package com.exchange.user.kyc;

/**
 * How much has been verified about a user, and therefore what they may do.
 *
 * <p>Tiered rather than a yes/no because every major exchange tiers it, and because the
 * rules we follow need the distinction: trading opens at {@link #BASIC}, while creating an
 * API key requires {@link #INTERMEDIATE} -- the usual split, where API access sits
 * behind Verified Plus rather than plain Verified. A boolean could not express that.
 *
 * <p>Roughly: {@code BASIC} is the "verified" tier (government ID plus a selfie),
 * {@code INTERMEDIATE} their "Verified Plus" (proof of address on top), and
 * {@code ADVANCED} their "Custom Limit" tier (source of wealth, PEP screening).
 *
 * <p><b>Declaration order is meaningful</b> -- {@link #atLeast} compares by it, so these
 * must stay in ascending order of privilege. Reordering them would silently change who is
 * allowed to trade. Note that this ordering is purely in Java: the column is a VARCHAR
 * holding the name, so the database is unaffected either way (AGENTS.md).
 */
public enum KycLevel {
    NONE,
    BASIC,
    INTERMEDIATE,
    ADVANCED;

    /** Whether this level satisfies a requirement of {@code required}. */
    public boolean atLeast(KycLevel required) {
        return compareTo(required) >= 0;
    }
}
