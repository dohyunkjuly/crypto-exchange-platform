package com.exchange.fee.usertier;

import com.exchange.fee.FeeProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A master's current tier for one product. A projection of {@link UserDailyVolume}, recomputed
 * daily by spot-settlement and rebuildable from it at any time (docs/reference/fee-tiers.md).
 *
 * <p>It exists so the order path is one indexed read rather than an aggregate. The window
 * is a trailing 30 days that slides daily — not a calendar month. Only a master has a row: a
 * sub pays its master's rate, and its volume counts towards it.
 */
@Entity
@Table(name = "fee_user_tiers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFeeTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeeProduct product;

    @Column(nullable = false)
    private int tierLevel;

    /** What the level was based on — so support can answer "why am I still VIP 1". */
    @Column(name = "volume_30d", nullable = false)
    private long volume30d;

    private LocalDate windowStart;

    private LocalDate windowEnd;

    @Column(nullable = false)
    private Instant computedAt;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    public UserFeeTier(Long userId, FeeProduct product) {
        this.userId = userId;
        this.product = product;
        this.computedAt = Instant.now();
    }

    public void assign(int tierLevel, long volume30d,
                       LocalDate windowStart, LocalDate windowEnd, Instant computedAt) {
        this.tierLevel = tierLevel;
        this.volume30d = volume30d;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.computedAt = computedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserFeeTier other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
