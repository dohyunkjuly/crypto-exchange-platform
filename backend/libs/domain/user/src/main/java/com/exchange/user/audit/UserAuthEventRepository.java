package com.exchange.user.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthEventRepository extends JpaRepository<UserAuthEvent, Long> {

    Page<UserAuthEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<UserAuthEvent> findByEventTypeAndCreatedAtAfter(AuthEventType eventType, Instant after);

    /**
     * Brute-force detection: how many failures of ONE KIND from this address recently.
     *
     * <p>The event type is not optional. Counting every failure outcome would let any
     * failing flow feed the login throttle -- a wrong 2FA code, a wrong password-reset
     * code -- and several of those can be triggered by someone who only knows the
     * address. Each flow caps its own attempts; this one is about logins.
     */
    long countByIpAddressAndEventTypeAndOutcomeAndCreatedAtAfter(
            String ipAddress, AuthEventType eventType, AuthOutcome outcome, Instant after);

    /** Credential stuffing against one address, including unregistered ones. Typed for
     *  the same reason as above. */
    long countByEmailAttemptedAndEventTypeAndOutcomeAndCreatedAtAfter(
            String emailAttempted, AuthEventType eventType, AuthOutcome outcome,
            Instant after);

}
