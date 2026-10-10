package com.exchange.copy.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

/**
 * One settlement of one copy account (V21): what was owed at that moment and what was paid.
 * Written once; the difference stays owed and the next settlement finds it in the same formula.
 */
@Entity
@Table(name = "copy_profit_shares")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyProfitShare implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID followId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 16)
    private ShareTrigger trigger;

    /** The Monday the week ended on, for a weekly settlement; null otherwise. */
    private LocalDate week;

    @Column(nullable = false)
    private int rateBps;

    @Column(nullable = false)
    private long realizedProfit;

    @Column(nullable = false)
    private long due;

    @Column(nullable = false)
    private long paid;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public CopyProfitShare(UUID id, UUID followId, ShareTrigger trigger, LocalDate week, int rateBps,
                           long realizedProfit, long due, long paid) {
        this.id = id;
        this.followId = followId;
        this.trigger = trigger;
        this.week = week;
        this.rateBps = rateBps;
        this.realizedProfit = realizedProfit;
        this.due = due;
        this.paid = paid;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopyProfitShare other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
