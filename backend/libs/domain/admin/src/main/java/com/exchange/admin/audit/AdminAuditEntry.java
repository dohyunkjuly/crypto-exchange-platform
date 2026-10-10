package com.exchange.admin.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only. Written in the same transaction as the change it records. */
@Entity
@Table(name = "admin_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when nobody was identified: a failed login. */
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private AdminAction action;

    private String targetType;

    private String targetId;

    @Column(name = "before_json")
    private String before;

    @Column(name = "after_json")
    private String after;

    @Column(nullable = false)
    private String ipAddress;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public AdminAuditEntry(Long adminId, AdminAction action, String targetType, String targetId,
                           String before, String after, String ipAddress) {
        this.adminId = adminId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.before = before;
        this.after = after;
        this.ipAddress = ipAddress;
    }
}
