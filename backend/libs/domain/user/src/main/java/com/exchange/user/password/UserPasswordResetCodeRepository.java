package com.exchange.user.password;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPasswordResetCodeRepository extends JpaRepository<UserPasswordResetCode, Long> {

    /** By user, then by code -- never by code alone, since six digits collide across a
     *  million values. */
    Optional<UserPasswordResetCode> findByUserIdAndUsedAtIsNull(Long userId);

    void deleteByUserIdAndUsedAtIsNull(Long userId);
}
