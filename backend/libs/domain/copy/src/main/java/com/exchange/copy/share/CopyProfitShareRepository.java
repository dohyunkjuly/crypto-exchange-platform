package com.exchange.copy.share;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopyProfitShareRepository extends JpaRepository<CopyProfitShare, UUID> {

    boolean existsByFollowIdAndWeek(UUID followId, LocalDate week);

    List<CopyProfitShare> findByFollowIdOrderByCreatedAtDesc(UUID followId);

    /** A page of the same, for the copy account's settlement history. */
    List<CopyProfitShare> findByFollowIdOrderByCreatedAtDesc(UUID followId, Pageable page);

    /** Everything a leader has been paid, across every follower they ever had. */
    @Query("SELECT COALESCE(SUM(s.paid), 0) FROM CopyProfitShare s "
            + "WHERE s.followId IN (SELECT f.id FROM CopyFollow f WHERE f.leaderId = :leaderId)")
    long paidToLeader(@Param("leaderId") UUID leaderId);
}
