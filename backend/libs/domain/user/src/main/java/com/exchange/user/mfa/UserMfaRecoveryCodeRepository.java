package com.exchange.user.mfa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMfaRecoveryCodeRepository extends JpaRepository<UserMfaRecoveryCode, Long> {

    /** Verification looks up by hash alone, then checks the code belongs to this user --
     *  the hash is globally unique, so this cannot collide across accounts. */
    Optional<UserMfaRecoveryCode> findByCodeHash(byte[] codeHash);

    List<UserMfaRecoveryCode> findByUserIdAndUsedAtIsNull(Long userId);

    /** Re-enrolling issues a fresh set, so the old ones must stop working. */
    void deleteByUserId(Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);
}
