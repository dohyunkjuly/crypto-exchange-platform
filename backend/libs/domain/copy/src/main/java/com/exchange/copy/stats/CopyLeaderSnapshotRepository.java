package com.exchange.copy.stats;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopyLeaderSnapshotRepository extends JpaRepository<CopyLeaderSnapshot, CopyLeaderSnapshot.Key> {

    boolean existsByLeaderIdAndDay(UUID leaderId, LocalDate day);

    /** The one before, whenever it was: a missed night leaves a longer step, not a broken chain. */
    Optional<CopyLeaderSnapshot> findFirstByLeaderIdAndDayLessThanOrderByDayDesc(UUID leaderId, LocalDate day);

    /** The latest, whenever it was: what the lead account was last valued at. */
    Optional<CopyLeaderSnapshot> findFirstByLeaderIdOrderByDayDesc(UUID leaderId);

    /** Several leaders' chains at once, each oldest first. */
    List<CopyLeaderSnapshot> findByLeaderIdInAndDayGreaterThanEqualOrderByLeaderIdAscDayAsc(Collection<UUID> leaderIds,
                                                                                            LocalDate from);

    /** A window of the chain, oldest first. */
    List<CopyLeaderSnapshot> findByLeaderIdAndDayBetweenOrderByDayAsc(UUID leaderId, LocalDate from, LocalDate to);
}
