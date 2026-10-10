package com.exchange.user.email;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEmailVerificationTokenRepository
        extends JpaRepository<UserEmailVerificationToken, Long> {

    /**
     * By user, then by code -- never by code alone.
     *
     * <p>Six digits collide across a million values, so a global lookup would let one
     * account's code open another's. Scoping to the user is what makes a short code safe
     * to compare at all.
     */
    Optional<UserEmailVerificationToken> findByUserIdAndUsedAtIsNull(Long userId);

    /** Issuing a new link invalidates the outstanding ones, so an old email in an inbox
     *  stops working the moment someone asks for a fresh one. */
    void deleteByUserIdAndUsedAtIsNull(Long userId);
}
