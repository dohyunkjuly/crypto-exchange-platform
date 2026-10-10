package com.exchange.spot.limit.symbol;

import com.exchange.spot.limit.override.SpotUserOrderLimitOverride;
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
 * A negotiated cap that beats {@link com.exchange.spot.symbol.SpotSymbol#getMaxOpenOrders()} /
 * {@code getMaxOpenConditionalOrders()} for one user on one symbol. No row means the user is
 * bound by that symbol's default, which is what almost everyone is.
 *
 * <p>The per-symbol counterpart to {@link SpotUserOrderLimitOverride}, which overrides the
 * exchange-wide totals instead. Either field here may be overridden alone -- {@code null}
 * means "inherit the symbol default for this field," not zero.
 */
@Entity
@Table(name = "spot_symbol_user_order_limit_overrides")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotSymbolUserOrderLimitOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long symbolId;

    private Integer maxOpenOrders;

    private Integer maxOpenConditionalOrders;

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

    public SpotSymbolUserOrderLimitOverride(Long userId, Long symbolId, Integer maxOpenOrders,
                                             Integer maxOpenConditionalOrders) {
        this.userId = userId;
        this.symbolId = symbolId;
        this.maxOpenOrders = maxOpenOrders;
        this.maxOpenConditionalOrders = maxOpenConditionalOrders;
    }

    public boolean isEffective(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotSymbolUserOrderLimitOverride other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
