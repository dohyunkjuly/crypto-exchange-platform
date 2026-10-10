package com.exchange.admin.audit;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAuditRepository extends JpaRepository<AdminAuditEntry, Long> {

    /** The latest rows of a few kinds, newest first: the treasury's history of moves. */
    List<AdminAuditEntry> findByActionInOrderByIdDesc(Collection<AdminAction> actions, Pageable pageable);

    long countByActionAndTargetIdAndCreatedAtAfter(AdminAction action, String targetId, Instant after);

    long countByActionAndIpAddressAndCreatedAtAfter(AdminAction action, String ipAddress, Instant after);

    /**
     * The log as staff read it back: newest first, narrowed by whoever, whatever and to whom.
     * Every filter is optional; the indexes on (admin_id, created_at) and (action, created_at)
     * carry the two ways anyone actually asks.
     */
    @Query("""
            SELECT e FROM AdminAuditEntry e
             WHERE (:adminId IS NULL OR e.adminId = :adminId)
               AND (:action IS NULL OR e.action = :action)
               AND (:targetType IS NULL OR e.targetType = :targetType)
               AND (:targetId IS NULL OR e.targetId = :targetId)
               AND (:from IS NULL OR e.createdAt >= :from)
               AND (:to IS NULL OR e.createdAt < :to)
             ORDER BY e.id DESC
            """)
    Page<AdminAuditEntry> search(@Param("adminId") Long adminId, @Param("action") AdminAction action,
                                 @Param("targetType") String targetType, @Param("targetId") String targetId,
                                 @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
