package com.exchange.spot.order;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpotOrderRepository extends JpaRepository<SpotOrder, UUID> {

    /**
     * Applies one fill as a single atomic increment, not a read-then-write -- safe for
     * concurrent fills on the SAME order, which {@code findById}/mutate/{@code save} is not
     * (see {@code SpotOrder.version}). MySQL evaluates WHERE and applies SET as one step per
     * row, so the second caller always sees the first's delta before deciding whether its own
     * still fits -- which is what keeps the overfill guard correct under concurrency.
     *
     * <p>{@code status} only advances, never backslides, so it does not matter which of two
     * concurrent fills lands first: a "still partial" update arriving after a "done" one is a
     * no-op, not a regression. A terminal status from another path is absorbing.
     *
     * <p>{@code fillsCompletely} is a boolean rather than {@code OrderStatus} because Hibernate
     * cannot type a bind parameter compared against an enum literal inside a bulk-update
     * {@code CASE} ({@code ConversionException: Could not determine ValueMapping}) -- found by
     * running it, not assumed.
     *
     * @return 1 if applied, 0 if rejected as an overfill or the order is gone. <b>The caller
     *         must check this</b>; nothing is thrown.
     */
    @Modifying
    @Query("""
            UPDATE SpotOrder o
            SET o.filledQuantity = o.filledQuantity + :fillQuantity,
                o.cumulativeQuoteQuantity = o.cumulativeQuoteQuantity + :fillQuoteQuantity,
                o.status = CASE
                    WHEN o.status IN (com.exchange.spot.order.OrderStatus.CANCELED,
                                      com.exchange.spot.order.OrderStatus.REJECTED,
                                      com.exchange.spot.order.OrderStatus.EXPIRED)
                        THEN o.status
                    WHEN :fillsCompletely = true THEN com.exchange.spot.order.OrderStatus.FILLED
                    WHEN o.status = com.exchange.spot.order.OrderStatus.FILLED THEN o.status
                    ELSE com.exchange.spot.order.OrderStatus.PARTIALLY_FILLED
                END,
                o.version = o.version + 1
            WHERE o.id = :orderId
              AND (o.quoteOrderQty IS NOT NULL OR o.filledQuantity + :fillQuantity <= o.quantity)
              AND (o.quoteOrderQty IS NULL
                   OR o.cumulativeQuoteQuantity + :fillQuoteQuantity <= o.quoteOrderQty)
            """)
    int applyFillAtomically(@Param("orderId") UUID orderId,
                            @Param("fillQuantity") long fillQuantity,
                            @Param("fillQuoteQuantity") long fillQuoteQuantity,
                            @Param("fillsCompletely") boolean fillsCompletely);

    /** A retried submit must return the original, not place a second order. */
    Optional<SpotOrder> findByUserIdAndClientOrderId(Long userId, String clientOrderId);

    /**
     * Claims the one amend an order may have in flight. A conditional UPDATE, not
     * load-and-save, because settlement updates the same row concurrently and an optimistic
     * lock failure here would turn an ordinary race into a 500.
     *
     * @return 1 if claimed, 0 if an amend is already pending or the order has left
     *         {@code statuses}
     */
    @Modifying
    @Query("""
            UPDATE SpotOrder o
            SET o.amendPending = true, o.version = o.version + 1
            WHERE o.id = :orderId AND o.amendPending = false AND o.status IN :statuses
            """)
    int claimAmend(@Param("orderId") UUID orderId, @Param("statuses") List<OrderStatus> statuses);

    /** Read fresh from the row, past whatever this transaction already loaded. */
    @Query("SELECT o.amendPending FROM SpotOrder o WHERE o.id = :orderId")
    boolean isAmendPending(@Param("orderId") UUID orderId);

    /** Order history by placement time. {@code symbolId} and {@code side} are optional. */
    @Query("""
            SELECT o FROM SpotOrder o
             WHERE o.userId = :userId
               AND (:symbolId IS NULL OR o.symbolId = :symbolId)
               AND (:side IS NULL OR o.side = :side)
               AND o.createdAt >= :from AND o.createdAt < :to
             ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<SpotOrder> findHistoryByCreatedAt(@Param("userId") Long userId,
                                           @Param("symbolId") Long symbolId,
                                           @Param("side") Side side,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);

    /** The same, by last change -- what a user scanning for recent fills and cancels wants. */
    @Query("""
            SELECT o FROM SpotOrder o
             WHERE o.userId = :userId
               AND (:symbolId IS NULL OR o.symbolId = :symbolId)
               AND (:side IS NULL OR o.side = :side)
               AND o.updatedAt >= :from AND o.updatedAt < :to
             ORDER BY o.updatedAt DESC, o.id DESC
            """)
    List<SpotOrder> findHistoryByUpdatedAt(@Param("userId") Long userId,
                                           @Param("symbolId") Long symbolId,
                                           @Param("side") Side side,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);

    /** The trading screen's open-orders poll. */
    List<SpotOrder> findByUserIdAndStatusIn(Long userId, List<OrderStatus> statuses);

    /**
     * Every customer's orders, newest first, for the admin console. Each filter is optional;
     * {@code anyStatus} stands in for a null list, which JPQL cannot test. Newest first rides
     * idx_spot_orders_created (V11), a customer filter (user_id, created_at), and a market
     * filter (symbol_id, created_at) (V24).
     */
    @Query("""
            SELECT o FROM SpotOrder o
             WHERE (:userId IS NULL OR o.userId = :userId)
               AND (:symbolId IS NULL OR o.symbolId = :symbolId)
               AND (:side IS NULL OR o.side = :side)
               AND (:anyStatus = true OR o.status IN :statuses)
               AND (:from IS NULL OR o.createdAt >= :from)
               AND (:to IS NULL OR o.createdAt < :to)
             ORDER BY o.createdAt DESC
            """)
    Page<SpotOrder> search(@Param("userId") Long userId, @Param("symbolId") Long symbolId, @Param("side") Side side,
                           @Param("anyStatus") boolean anyStatus, @Param("statuses") Collection<OrderStatus> statuses,
                           @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Rebuilding the book on engine restart: every live order for one market. */
    List<SpotOrder> findBySymbolIdAndStatusIn(Long symbolId, List<OrderStatus> statuses);

    /** Account-wide open-order limit (SpotExchangeOrderLimits.maxNumOrders / a
     *  SpotUserOrderLimitOverride). */
    long countByUserIdAndStatusIn(Long userId, List<OrderStatus> statuses);

    /** Per-symbol open-order limit (SpotSymbol.maxOpenOrders / a
     *  SpotSymbolUserOrderLimitOverride). */
    long countByUserIdAndSymbolIdAndStatusIn(Long userId, Long symbolId, List<OrderStatus> statuses);

    /** Account-wide algo/conditional-order limit -- a separate cap from the plain
     *  order limit above (SpotExchangeOrderLimits.maxNumAlgoOrders). */
    long countByUserIdAndOrderTypeInAndStatusIn(
            Long userId, List<OrderType> orderTypes, List<OrderStatus> statuses);

    /** Per-symbol algo/conditional-order limit (SpotSymbol.maxOpenConditionalOrders /
     *  a SpotSymbolUserOrderLimitOverride). */
    long countByUserIdAndSymbolIdAndOrderTypeInAndStatusIn(Long userId, Long symbolId,
            List<OrderType> orderTypes, List<OrderStatus> statuses);
}
