package com.exchange.copy.follow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CopyFollowRepository extends JpaRepository<CopyFollow, UUID> {

    /** Everyone copying a leader: what each of that leader's orders is repeated for. */
    List<CopyFollow> findByLeaderIdAndStatus(UUID leaderId, FollowStatus status);

    long countByLeaderIdAndStatus(UUID leaderId, FollowStatus status);

    List<CopyFollow> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Whose copy account an order belongs to. */
    Optional<CopyFollow> findByCopyUserId(Long copyUserId);

    List<CopyFollow> findByStatus(FollowStatus status);

    /**
     * Copy accounts still copying a leader who no longer leads -- suspended by staff, or closed
     * by the leader. The copier closes them: whoever changed the leader only changed a status.
     */
    @Query("SELECT f FROM CopyFollow f WHERE f.status = com.exchange.copy.follow.FollowStatus.ACTIVE "
            + "AND f.leaderId IN (SELECT l.id FROM CopyLeader l WHERE l.status IN ("
            + "com.exchange.copy.leader.LeaderStatus.SUSPENDED, com.exchange.copy.leader.LeaderStatus.CLOSED))")
    List<CopyFollow> findActiveOfLeadersNoLongerLeading();

    /**
     * One more failed copy. Its own transaction, like the two below, so a caller needs none.
     * A single statement, not load-and-save: the follower stopping, or a
     * second market's event, may be writing the row at the same moment.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CopyFollow f SET f.failuresInRow = f.failuresInRow + 1, f.version = f.version + 1 "
            + "WHERE f.id = :id AND f.status = com.exchange.copy.follow.FollowStatus.ACTIVE")
    int countFailure(@Param("id") UUID id);

    /** A copy that filled: the count starts again. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CopyFollow f SET f.failuresInRow = 0, f.version = f.version + 1 "
            + "WHERE f.id = :id AND f.failuresInRow <> 0")
    int clearFailures(@Param("id") UUID id);

    /**
     * ACTIVE to CLOSING, once: whoever gets the row starts the closing, and nobody else does.
     *
     * @return 1 for the caller that closed it, 0 if it was not active
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CopyFollow f SET f.status = com.exchange.copy.follow.FollowStatus.CLOSING, "
            + "f.closeReason = :reason, f.version = f.version + 1 "
            + "WHERE f.id = :id AND f.status = com.exchange.copy.follow.FollowStatus.ACTIVE")
    int startClosing(@Param("id") UUID id, @Param("reason") CloseReason reason);
}
