package com.exchange.wallet.treasury;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeeHourlyTotalRepository extends JpaRepository<FeeHourlyTotal, FeeHourlyTotal.Key> {

    /**
     * The instant the rollups are complete to: the end of the newest hour recorded. Empty
     * until the first hour is rolled up, and never ahead of what is stored -- an optimistic
     * answer here would leave the live half of a sum starting after rows it has not counted.
     */
    @Query("SELECT MAX(t.periodStart) FROM FeeHourlyTotal t")
    Optional<Instant> newestPeriodStart();

    /** Rolled-up flow for one asset, strictly before {@code end}. Signed, as stored. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM FeeHourlyTotal t "
            + "WHERE t.assetId = :assetId AND t.periodStart < :end")
    long sumBefore(@Param("assetId") Long assetId, @Param("end") Instant end);

    boolean existsByPeriodStart(Instant periodStart);
}
