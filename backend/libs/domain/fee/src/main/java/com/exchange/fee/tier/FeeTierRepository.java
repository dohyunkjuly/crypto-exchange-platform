package com.exchange.fee.tier;

import com.exchange.fee.FeeProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeeTierRepository extends JpaRepository<FeeTier, Long> {

    /** The whole ladder for a product. Small enough to load and cache wholesale. */
    List<FeeTier> findByProductOrderByLevelAsc(FeeProduct product);

    Optional<FeeTier> findByProductAndLevel(FeeProduct product, int level);

    /**
     * The highest rung this volume reaches. Used by the daily tier job, not on the order
     * path — order time reads the already-resolved assignment instead.
     */
    @Query("""
            SELECT t FROM FeeTier t
             WHERE t.product = :product
               AND t.min30dVolume <= :volume
             ORDER BY t.min30dVolume DESC
             LIMIT 1
            """)
    Optional<FeeTier> findHighestReached(@Param("product") FeeProduct product,
                                         @Param("volume") long volume);
}
