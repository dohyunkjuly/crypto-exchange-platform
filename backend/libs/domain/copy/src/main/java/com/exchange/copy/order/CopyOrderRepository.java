package com.exchange.copy.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopyOrderRepository extends JpaRepository<CopyOrder, UUID> {

    /** The copy a fill or a cancel in a copy account belongs to. */
    Optional<CopyOrder> findByOrderId(UUID orderId);

    /** Has this lead order already been copied for this follow. */
    boolean existsByLeadOrderIdAndFollowId(UUID leadOrderId, UUID followId);

    /** A copy account's copies, newest first: what its owner is shown. */
    List<CopyOrder> findByFollowIdOrderByCreatedAtDesc(UUID followId, Pageable page);

    /** The copies among a batch of events' orders: one read, not one per event. */
    List<CopyOrder> findByOrderIdIn(Collection<UUID> orderIds);

    /** Every market a copy account has been given an order in. */
    @Query("SELECT DISTINCT o.symbolId FROM CopyOrder o WHERE o.followId = :followId AND o.orderId IS NOT NULL")
    List<Long> marketsOf(@Param("followId") UUID followId);

    /**
     * How a leader's followers' copies ended since {@code since}, as {@code [status, count]}.
     * Closing sales copy no lead order and are not counted.
     */
    @Query("SELECT o.status, COUNT(o) FROM CopyOrder o WHERE o.leadOrderId IS NOT NULL AND o.createdAt >= :since "
            + "AND o.followId IN (SELECT f.id FROM CopyFollow f WHERE f.leaderId = :leaderId) GROUP BY o.status")
    List<Object[]> outcomesOfLeader(@Param("leaderId") UUID leaderId, @Param("since") Instant since);
}
