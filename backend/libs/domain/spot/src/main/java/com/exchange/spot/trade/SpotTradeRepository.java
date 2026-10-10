package com.exchange.spot.trade;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpotTradeRepository extends JpaRepository<SpotTrade, UUID> {

    /** The tape, and the source for every candle interval. */
    Page<SpotTrade> findBySymbolIdOrderByExecutedAtDesc(Long symbolId, Pageable pageable);

    List<SpotTrade> findBySymbolIdAndExecutedAtBetween(Long symbolId, Instant from, Instant to);

    /**
     * One market's quote volume and trade count over {@code [from, to)}, for the admin dashboard.
     * Per symbol rather than grouped across all of them: (symbol_id, executed_at) is the index
     * there is, and markets are few.
     */
    @Query("SELECT COALESCE(SUM(t.quoteQuantity), 0), COUNT(t) FROM SpotTrade t "
            + "WHERE t.symbolId = :symbolId AND t.executedAt >= :from AND t.executedAt < :to")
    List<Object[]> volumeBetween(@Param("symbolId") Long symbolId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * One market's volume per buyer over {@code [from, to)}, for the daily fee-tier recount: each
     * row is {@code (userId, quote volume, trade count)}. Buyers and sellers are two queries
     * rather than a UNION because JPQL has none, and the same index serves both.
     */
    @Query("SELECT t.buyerUserId, COALESCE(SUM(t.quoteQuantity), 0), COUNT(t) FROM SpotTrade t "
            + "WHERE t.symbolId = :symbolId AND t.executedAt >= :from AND t.executedAt < :to "
            + "GROUP BY t.buyerUserId")
    List<Object[]> buyerVolumeBetween(@Param("symbolId") Long symbolId, @Param("from") Instant from,
                                      @Param("to") Instant to);

    /** The other side of {@link #buyerVolumeBetween}. */
    @Query("SELECT t.sellerUserId, COALESCE(SUM(t.quoteQuantity), 0), COUNT(t) FROM SpotTrade t "
            + "WHERE t.symbolId = :symbolId AND t.executedAt >= :from AND t.executedAt < :to "
            + "GROUP BY t.sellerUserId")
    List<Object[]> sellerVolumeBetween(@Param("symbolId") Long symbolId, @Param("from") Instant from,
                                       @Param("to") Instant to);

    /** One account's fills in one market past a sequence, in order: what a position has yet to count. */
    @Query("SELECT t FROM SpotTrade t WHERE t.symbolId = :symbolId AND t.sequence > :after "
            + "AND (t.buyerUserId = :userId OR t.sellerUserId = :userId) ORDER BY t.sequence")
    List<SpotTrade> ofAccountAfter(@Param("userId") Long userId, @Param("symbolId") Long symbolId,
                                   @Param("after") long after);

    /** The market's last trade before {@code before}: its price is the close of whatever period ends there. */
    @Query("SELECT t FROM SpotTrade t WHERE t.symbolId = :symbolId AND t.executedAt < :before "
            + "ORDER BY t.executedAt DESC, t.sequence DESC LIMIT 1")
    Optional<SpotTrade> lastBefore(@Param("symbolId") Long symbolId, @Param("before") Instant before);

    /** Every fill of one order, for the order-detail view. */
    List<SpotTrade> findByBuyerOrderIdOrSellerOrderId(UUID buyerOrderId, UUID sellerOrderId);

    /** The engine's replay guard — a sequence already persisted must not be applied twice. */
    Optional<SpotTrade> findBySymbolIdAndSequence(Long symbolId, long sequence);

    /** The same guard as {@link #findBySymbolIdAndSequence}, checked for many sequences
     *  in one round trip instead of one per trade -- which of {@code sequences} has
     *  already been recorded for this symbol. */
    @Query("SELECT t.sequence FROM SpotTrade t WHERE t.symbolId = :symbolId "
            + "AND t.sequence IN :sequences")
    List<Long> findExistingSequences(@Param("symbolId") Long symbolId,
                                     @Param("sequences") Collection<Long> sequences);

    /**
     * One user's fills, newest first. {@code buys}/{@code sells} pick which side of the trade
     * they were on; a trade never has the same user on both, so neither can double a row.
     * {@code symbolId} is optional.
     */
    @Query("""
            SELECT t FROM SpotTrade t
             WHERE ((:buys = true AND t.buyerUserId = :userId)
                 OR (:sells = true AND t.sellerUserId = :userId))
               AND (:symbolId IS NULL OR t.symbolId = :symbolId)
               AND t.executedAt >= :from AND t.executedAt < :to
             ORDER BY t.executedAt DESC, t.id DESC
            """)
    List<SpotTrade> findUserTrades(@Param("userId") Long userId,
                                   @Param("symbolId") Long symbolId,
                                   @Param("buys") boolean buys,
                                   @Param("sells") boolean sells,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    /**
     * One side of a customer's trades, newest first, each read along its own index. The admin
     * console merges the two: an OR across buyer and seller would sort every trade they have.
     */
    @Query("""
            SELECT t FROM SpotTrade t
             WHERE t.buyerUserId = :userId
               AND (:symbolId IS NULL OR t.symbolId = :symbolId)
               AND (:from IS NULL OR t.executedAt >= :from)
               AND (:to IS NULL OR t.executedAt < :to)
             ORDER BY t.executedAt DESC, t.id DESC
            """)
    List<SpotTrade> boughtBy(@Param("userId") Long userId, @Param("symbolId") Long symbolId,
                             @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** The other side of {@link #boughtBy}. */
    @Query("""
            SELECT t FROM SpotTrade t
             WHERE t.sellerUserId = :userId
               AND (:symbolId IS NULL OR t.symbolId = :symbolId)
               AND (:from IS NULL OR t.executedAt >= :from)
               AND (:to IS NULL OR t.executedAt < :to)
             ORDER BY t.executedAt DESC, t.id DESC
            """)
    List<SpotTrade> soldBy(@Param("userId") Long userId, @Param("symbolId") Long symbolId,
                           @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /**
     * Every trade, newest first, read backwards along the primary key: trade ids lead with their
     * timestamp (SymbolState.tradeId), so a time range is a key range ({@code Ulid.floor}).
     */
    @Query("""
            SELECT t FROM SpotTrade t
             WHERE (:symbolId IS NULL OR t.symbolId = :symbolId)
               AND (:lo IS NULL OR t.id >= :lo)
               AND (:hi IS NULL OR t.id < :hi)
             ORDER BY t.id DESC
            """)
    Slice<SpotTrade> latest(@Param("symbolId") Long symbolId, @Param("lo") UUID lo, @Param("hi") UUID hi,
                            Pageable pageable);
}
