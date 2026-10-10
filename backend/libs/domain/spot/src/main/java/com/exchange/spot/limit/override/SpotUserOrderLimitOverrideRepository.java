package com.exchange.spot.limit.override;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotUserOrderLimitOverrideRepository
        extends JpaRepository<SpotUserOrderLimitOverride, Long> {

    Optional<SpotUserOrderLimitOverride> findByUserId(Long userId);

    /** Overrides due to lapse, so somebody renegotiates rather than forgetting. */
    List<SpotUserOrderLimitOverride> findByExpiresAtBefore(Instant before);
}
