package com.exchange.wallet.balance;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BalanceRepository extends JpaRepository<Balance, Long> {

    Optional<Balance> findByUserIdAndAssetId(Long userId, Long assetId);

    /** The portfolio screen. */
    List<Balance> findByUserId(Long userId);

    /** A master's sub-accounts at once, so one screen of them is one query rather than one each. */
    List<Balance> findByUserIdIn(Collection<Long> userIds);

    /**
     * What every account other than the given ones holds, per asset: available, locked and the
     * number of holders. The treasury screen passes the exchange's own accounts here, because
     * counting the house's money as customers' would overstate what is owed
     * (docs/reference/treasury.md).
     *
     * <p>Grouped in the database rather than by loading rows: this is one row per user per
     * asset they have touched, which is small next to trades but too big to bring back.
     */
    @Query("SELECT b.assetId, COALESCE(SUM(b.available), 0), COALESCE(SUM(b.locked), 0), COUNT(b) "
            + "FROM Balance b WHERE b.userId NOT IN :exceptUserIds GROUP BY b.assetId")
    List<Object[]> totalsByAssetExcluding(@Param("exceptUserIds") Collection<Long> exceptUserIds);

    /**
     * SELECT ... FOR UPDATE. Use this on the write path -- reserving funds for an order,
     * settling a fill -- where the read-modify-write must not interleave with another
     * transaction. The @Version column catches a lost update after the fact; this stops
     * one happening.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Balance b WHERE b.userId = :userId AND b.assetId = :assetId")
    Optional<Balance> findForUpdate(@Param("userId") Long userId, @Param("assetId") Long assetId);
}
