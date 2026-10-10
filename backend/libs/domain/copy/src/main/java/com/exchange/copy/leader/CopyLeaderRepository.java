package com.exchange.copy.leader;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopyLeaderRepository extends JpaRepository<CopyLeader, UUID> {

    Optional<CopyLeader> findByUserId(Long userId);

    /** Whose lead account placed this order: the read the order path does for a LEAD sub. */
    Optional<CopyLeader> findByLeadUserId(Long leadUserId);

    List<CopyLeader> findByStatusOrderByCreatedAtAsc(LeaderStatus status);

    /** For staff: the queue oldest first when asked for one status, everyone newest first otherwise. */
    Page<CopyLeader> findByStatus(LeaderStatus status, Pageable page);

    Optional<CopyLeader> findByDisplayName(String displayName);
}
