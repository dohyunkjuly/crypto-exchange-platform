package com.exchange.user.identity.external;

/**
 * An identity provider a user can sign in through.
 *
 * <p>Stored as a string with a CHECK constraint (see V1__user.sql), so adding one is a migration.
 * Never rename or remove a value: the name is on disk in every linked row.
 */
public enum ExternalProvider {
    GOOGLE,
    /** Not implemented; listed so the constraint does not need changing twice. */
    APPLE
}
