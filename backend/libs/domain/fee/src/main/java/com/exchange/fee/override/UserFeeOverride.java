package com.exchange.fee.override;

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

/**
 * A negotiated rate that beats whatever tier the user's volume earned. No row means they
 * pay the published schedule, which is what almost everyone does.
 *
 * <p>Per product, so a futures rebate does not silently apply to spot.
 */
@Entity
@Table(name = "fee_user_overrides")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFeeOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private FeeProduct product;

    @Column(nullable = false)
    private int makerFeeBps;

    @Column(nullable = false)
    private int takerFeeBps;

    @Column(length = 255)
    private String reason;

    /** The admin who approved it. Overrides are what an auditor asks about first. */
    private Long grantedBy;

    /** Negotiated rates expire so they get renegotiated rather than forgotten. */
    private Instant expiresAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public UserFeeOverride(Long userId, FeeProduct product, int makerFeeBps, int takerFeeBps) {
        this.userId = userId;
        this.product = product;
        this.makerFeeBps = makerFeeBps;
        this.takerFeeBps = takerFeeBps;
    }

    public FeeRates rates() {
        return new FeeRates(makerFeeBps, takerFeeBps);
    }

    public boolean isEffective(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserFeeOverride other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
