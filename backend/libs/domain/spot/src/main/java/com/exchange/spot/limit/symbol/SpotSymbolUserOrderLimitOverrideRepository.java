package com.exchange.spot.limit.symbol;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotSymbolUserOrderLimitOverrideRepository
        extends JpaRepository<SpotSymbolUserOrderLimitOverride, Long> {

    Optional<SpotSymbolUserOrderLimitOverride> findByUserIdAndSymbolId(Long userId,
                                                                        Long symbolId);

    List<SpotSymbolUserOrderLimitOverride> findByUserId(Long userId);

    /** Overrides due to lapse, so somebody renegotiates rather than forgetting. */
    List<SpotSymbolUserOrderLimitOverride> findByExpiresAtBefore(Instant before);
}
