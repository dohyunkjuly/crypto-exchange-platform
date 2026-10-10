package com.exchange.spot.orderfilllog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpotOrderFillLogRepository
        extends JpaRepository<SpotOrderFillLog, SpotOrderFillLog.Key> {

    /** Which of {@code tradeIds} has this order already counted -- everything NOT in
     *  the result is genuinely new and belongs in this attempt's aggregate. */
    @Query("SELECT f.tradeId FROM SpotOrderFillLog f "
            + "WHERE f.orderId = :orderId AND f.tradeId IN :tradeIds")
    List<UUID> findExistingTradeIds(@Param("orderId") UUID orderId,
                                    @Param("tradeIds") Collection<UUID> tradeIds);
}
