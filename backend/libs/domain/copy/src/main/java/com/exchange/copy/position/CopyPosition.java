package com.exchange.copy.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * What a copy account holds of one asset and what that cost, so a sale knows its profit without
 * anything being valued at a market price (docs/reference/copy-trading.md). Brought up to date
 * from {@code spot_trades} when a profit share is settled, never on the fill path.
 */
@Entity
@Table(name = "copy_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID followId;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    @Setter
    private long quantity;

    /** What {@link #quantity} cost in USDT, fees included, scaled by 1e8. */
    @Column(nullable = false)
    @Setter
    private long cost;

    /** The market's trade sequence this position has been counted up to (V21). */
    @Column(nullable = false)
    @Setter
    private long lastSequence;

    @Version
    private long version;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    public CopyPosition(UUID followId, Long assetId) {
        this.followId = followId;
        this.assetId = assetId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopyPosition other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
