package com.exchange.fee.usertier;

import com.exchange.fee.FeeProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFeeTierRepository extends JpaRepository<UserFeeTier, Long> {

    /** The order path: one indexed read, and the reason this table exists. */
    Optional<UserFeeTier> findByUserIdAndProduct(Long userId, FeeProduct product);

    List<UserFeeTier> findByUserId(Long userId);

    /** Everyone currently tiered -- the daily job revisits them all, so a quiet VIP is demoted. */
    List<UserFeeTier> findByProduct(FeeProduct product);
}
