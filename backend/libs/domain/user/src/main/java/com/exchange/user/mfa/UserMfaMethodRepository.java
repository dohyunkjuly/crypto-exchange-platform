package com.exchange.user.mfa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMfaMethodRepository extends JpaRepository<UserMfaMethod, Long> {

    /** The factors a login challenge can actually offer: verified and not revoked. */
    List<UserMfaMethod> findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(Long userId);

    List<UserMfaMethod> findByUserId(Long userId);

    /** WebAuthn assertion arrives with a credential id and nothing else. */
    Optional<UserMfaMethod> findByCredentialId(byte[] credentialId);

    boolean existsByUserIdAndMethodTypeAndVerifiedAtIsNotNullAndRevokedAtIsNull(
            Long userId, MfaMethodType methodType);

    /** The gate every sensitive operation asks: does this account have ANY active factor?
     *  Kept as a count query rather than loading the rows -- ApiKeyService and the trading
     *  gate only need the yes/no. */
    boolean existsByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(Long userId);

    /** An enrolment in progress: verified_at still null, not yet revoked. At most one is
     *  kept per user -- starting a new enrolment revokes any earlier pending one, so a
     *  half-finished setup can never be resumed with a stale secret. */
    Optional<UserMfaMethod> findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
            Long userId, MfaMethodType methodType);
}
