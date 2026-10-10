package com.exchange.spot.limit.override;

import com.exchange.spot.limit.exchange.SpotExchangeOrderLimits;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A negotiated cap that beats {@link SpotExchangeOrderLimits} for one user. No row means the
 * user is bound by the exchange-wide default, which is what almost everyone is.
 *
 * <p>Either field may be overridden alone -- {@code null} means "inherit the exchange
 * default for this field," not zero.
 */
@Entity
@Table(name = "spot_user_order_limit_overrides")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotUserOrderLimitOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    private Integer maxNumOrders;

    private Integer maxNumAlgoOrders;

    @Column(length = 255)
    private String reason;

    /** The admin who approved it. Order limit overrides are what an auditor asks about first. */
    private Long grantedBy;

    /** Negotiated caps expire so they get renegotiated rather than forgotten. */
    private Instant expiresAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public SpotUserOrderLimitOverride(Long userId, Integer maxNumOrders,
                                       Integer maxNumAlgoOrders) {
        this.userId = userId;
        this.maxNumOrders = maxNumOrders;
        this.maxNumAlgoOrders = maxNumAlgoOrders;
    }

    public boolean isEffective(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotUserOrderLimitOverride other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
