package com.exchange.fee.tier;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeRates;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** One rung of the published schedule for one product. */
@Entity
@Table(name = "fee_tiers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeeTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private FeeProduct product;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int level;

    @Column(nullable = false, length = 32)
    private String name;

    /** Trailing 30-day volume required, in the quote asset, scaled by 1e8. */
    @Column(name = "min_30d_volume", nullable = false)
    private long min30dVolume;

    private Long minAssetId;

    private Long minAssetBalance;

    @Column(nullable = false)
    private int makerFeeBps;

    @Column(nullable = false)
    private int takerFeeBps;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public FeeTier(FeeProduct product, int level, String name,
                   long min30dVolume, int makerFeeBps, int takerFeeBps) {
        this.product = product;
        this.level = level;
        this.name = name;
        this.min30dVolume = min30dVolume;
        this.makerFeeBps = makerFeeBps;
        this.takerFeeBps = takerFeeBps;
    }

    public FeeRates rates() {
        return new FeeRates(makerFeeBps, takerFeeBps);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FeeTier other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
