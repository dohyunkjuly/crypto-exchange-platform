package com.exchange.admin.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminSessionRepository extends JpaRepository<AdminSession, Long> {

    Optional<AdminSession> findByTokenHash(byte[] tokenHash);

    List<AdminSession> findByAdminIdAndRevokedAtIsNullAndExpiresAtAfter(Long adminId, Instant now);
}
