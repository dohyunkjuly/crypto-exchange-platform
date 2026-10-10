package com.exchange.fee.override;

import com.exchange.fee.FeeProduct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFeeOverrideRepository extends JpaRepository<UserFeeOverride, Long> {

    Optional<UserFeeOverride> findByUserIdAndProduct(Long userId, FeeProduct product);

    List<UserFeeOverride> findByUserId(Long userId);

    /** Overrides due to lapse, so somebody renegotiates rather than forgetting. */
    List<UserFeeOverride> findByExpiresAtBefore(Instant before);
}
