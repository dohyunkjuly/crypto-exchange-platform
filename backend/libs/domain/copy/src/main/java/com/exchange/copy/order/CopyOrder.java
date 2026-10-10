package com.exchange.copy.order;

import com.exchange.spot.order.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

/**
 * One order placed, or not placed, in a copy account (docs/reference/copy-trading.md).
 *
 * <p>The table's unique key on {@code (leadOrderId, followId)} is what makes a copy happen once:
 * an event delivered twice finds the row (docs/reference/delivery-guarantees.md).
 */
@Entity
@Table(name = "copy_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyOrder implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID followId;

    /** The lead order this copies; null for a sale made to close the account. */
    private UUID leadOrderId;

    /** The order in the copy account; null when nothing could be placed. */
    private UUID orderId;

    @Column(nullable = false)
    private Long symbolId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter
    private CopyOrderStatus status;

    /** Why it failed or was skipped, for the follower's page and the leader's success rate. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Setter
    private CopyOrderReason reason;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = false;

    public CopyOrder(UUID id, UUID followId, UUID leadOrderId, UUID orderId, Long symbolId, Side side,
                     CopyOrderStatus status, CopyOrderReason reason) {
        this.id = id;
        this.followId = followId;
        this.leadOrderId = leadOrderId;
        this.orderId = orderId;
        this.symbolId = symbolId;
        this.side = side;
        this.status = status;
        this.reason = reason;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Saved once as new; the same object saved again is an update. */
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopyOrder other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
