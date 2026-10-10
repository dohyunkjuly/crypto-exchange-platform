package com.exchange.fee.volume;

import com.exchange.fee.FeeProduct;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDailyVolumeRepository extends JpaRepository<UserDailyVolume, Long> {

    /**
     * Wipes one day so it can be written again from {@code spot_trades}: the recount is a
     * delete and a set of inserts in one transaction, not an upsert, so a rerun leaves exactly
     * the rows the trades imply and nothing an earlier run got wrong.
     *
     * <p>One statement, run at once. Loading the rows and removing them one by one would leave
     * the deletes for the flush, and the flush runs inserts first: the second recount of a day
     * that already has rows would then hit the unique key before the old rows were gone.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserDailyVolume v WHERE v.tradeDate = :tradeDate AND v.product = :product")
    void deleteByTradeDateAndProduct(@Param("tradeDate") LocalDate tradeDate, @Param("product") FeeProduct product);

    /** The window summed over a whole family -- a master and its subs share one tier. */
    @Query("""
            SELECT COALESCE(SUM(v.volume), 0) FROM UserDailyVolume v
             WHERE v.userId IN :userIds
               AND v.product = :product
               AND v.tradeDate >= :from
               AND v.tradeDate <= :to
            """)
    long sumVolume(@Param("userIds") Collection<Long> userIds,
                   @Param("product") FeeProduct product,
                   @Param("from") LocalDate from,
                   @Param("to") LocalDate to);

    /** Everyone the daily job needs to look at — users with any volume in the window. */
    @Query("""
            SELECT DISTINCT v.userId FROM UserDailyVolume v
             WHERE v.product = :product AND v.tradeDate >= :from
            """)
    List<Long> findUserIdsWithVolumeSince(@Param("product") FeeProduct product,
                                          @Param("from") LocalDate from);
}
