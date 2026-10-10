package com.exchange.user.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHash(byte[] tokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** Logout everywhere. Bulk update, so it bypasses the persistence context. */
    @Modifying
    @Query("""
            UPDATE UserSession s
               SET s.revokedAt = :at, s.revokedReason = :reason
             WHERE s.userId = :userId AND s.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("reason") SessionRevokedReason reason,
                         @Param("at") Instant at);

    /** Every session but one -- a password change keeps the device it was made on. */
    @Modifying
    @Query("""
            UPDATE UserSession s
               SET s.revokedAt = :at, s.revokedReason = :reason
             WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.id <> :keepId
            """)
    int revokeAllForUserExcept(@Param("userId") Long userId,
                               @Param("keepId") Long keepId,
                               @Param("reason") SessionRevokedReason reason,
                               @Param("at") Instant at);
}
