package com.exchange.fee.volume;

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
 * One user's traded volume on one day for one product.
 *
 * <p>The table that makes tiering affordable: 30-day volume becomes a sum over thirty rows
 * rather than an aggregate over every trade ever.
 *
 * <p>Recounted from {@code spot_trades} once a day, never written on the fill path: a day is
 * deleted and rewritten whole, so the table can only ever say what the trades say
 * (docs/reference/fee-tiers.md).
 */
@Entity
@Table(name = "fee_user_daily_volumes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDailyVolume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeeProduct product;

    /** Notional in the volume asset, scaled by 1e8: other quotes are converted at the day's close. */
    @Column(nullable = false)
    private long volume;

    @Column(nullable = false)
    private long tradeCount;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    public UserDailyVolume(Long userId, LocalDate tradeDate, FeeProduct product, long volume, long tradeCount) {
        this.userId = userId;
        this.tradeDate = tradeDate;
        this.product = product;
        this.volume = volume;
        this.tradeCount = tradeCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserDailyVolume other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
