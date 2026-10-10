package com.exchange.spot.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SpotCommandOutboxRepository extends JpaRepository<SpotCommandOutbox, UUID> {

    /** Is something about this order still on its way to the engine? */
    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o "
            + "WHERE o.orderId = :orderId AND o.publishedAt IS NULL")
    boolean hasPendingForOrder(@Param("orderId") UUID orderId);

    /** Is anything of this user's still on its way? What a cancel-all must wait behind. */
    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o "
            + "WHERE o.userId = :userId AND o.publishedAt IS NULL")
    boolean hasPendingForUser(@Param("userId") Long userId);

    /** The same two, asked by a row about the rows ahead of it -- so not counting itself. */
    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o "
            + "WHERE o.orderId = :orderId AND o.publishedAt IS NULL AND o.id <> :self")
    boolean hasOtherPendingForOrder(@Param("orderId") UUID orderId, @Param("self") UUID self);

    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o "
            + "WHERE o.userId = :userId AND o.publishedAt IS NULL AND o.id <> :self")
    boolean hasOtherPendingForUser(@Param("userId") Long userId, @Param("self") UUID self);

    /**
     * What the sweeper takes, oldest first. Not locked: two instances sweeping at once may
     * both send a row, and the engine skips the second copy. A lock would have to be held
     * across the publish, and a database connection with it.
     */
    @Query("SELECT o FROM SpotCommandOutbox o "
            + "WHERE o.publishedAt IS NULL AND o.createdAt < :before ORDER BY o.createdAt")
    List<SpotCommandOutbox> findPending(@Param("before") Instant before, Pageable page);

    /**
     * Is a command written BEFORE this one, for the same order, still unpublished? What the
     * publisher asks before sending a row that had such a sibling when it was written.
     * {@code self} is excluded because the column keeps microseconds and {@code createdAt}
     * here may carry nanoseconds: a row would otherwise count as older than itself.
     */
    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o WHERE o.orderId = :orderId "
            + "AND o.publishedAt IS NULL AND o.createdAt <= :createdAt AND o.id <> :self")
    boolean hasOlderPendingForOrder(@Param("orderId") UUID orderId, @Param("createdAt") Instant createdAt,
                                    @Param("self") UUID self);

    @Query("SELECT COUNT(o) > 0 FROM SpotCommandOutbox o WHERE o.userId = :userId "
            + "AND o.publishedAt IS NULL AND o.createdAt <= :createdAt AND o.id <> :self")
    boolean hasOlderPendingForUser(@Param("userId") Long userId, @Param("createdAt") Instant createdAt,
                                   @Param("self") UUID self);

    /** Called with no transaction around it, so this is the whole of one. */
    @Modifying
    @Transactional
    @Query("UPDATE SpotCommandOutbox o SET o.publishedAt = :at "
            + "WHERE o.id = :id AND o.publishedAt IS NULL")
    int markPublished(@Param("id") UUID id, @Param("at") Instant at);

    /** A whole batch in one statement: the publisher stamps what a batch had acknowledged. */
    @Modifying
    @Transactional
    @Query("UPDATE SpotCommandOutbox o SET o.publishedAt = :at "
            + "WHERE o.id IN :ids AND o.publishedAt IS NULL")
    int markPublishedAll(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("DELETE FROM SpotCommandOutbox o WHERE o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
