package com.exchange.spot.limit.exchange;

import com.exchange.spot.limit.override.SpotUserOrderLimitOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Account-wide order caps, across every symbol — the counterpart to
 * {@link com.exchange.spot.symbol.SpotSymbol#getMaxOpenOrders()} and
 * {@code getMaxOpenConditionalOrders()}, which cap one account on one symbol only.
 * Without this, trading enough symbols at their per-symbol limits still adds up to an
 * unbounded number of open orders overall.
 *
 * <p>Exactly one row exists, {@link #SINGLETON_ID}, enforced by a check constraint —
 * there is one exchange, so there is one set of exchange-wide limits. A user with a
 * {@link SpotUserOrderLimitOverride} row is bound by that instead, for either field.
 */
@Entity
@Table(name = "spot_exchange_order_limits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotExchangeOrderLimits {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer id;

    /** Per user, across every symbol. The account-wide open-order cap. */
    @Column(nullable = false)
    private int maxNumOrders;

    /** Per user, across every symbol; a subset of the above.
     *  The account-wide open-algo-order cap. */
    @Column(nullable = false)
    private int maxNumAlgoOrders;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;
}
