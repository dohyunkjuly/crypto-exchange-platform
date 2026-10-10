package com.exchange.spot.symbol;

import com.exchange.common.money.Money;
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
 * A tradable market — BTC against USDT. Base is what you buy and sell; quote is what you
 * price and pay in.
 *
 * <p>Most of this class is the rule set every order is checked against. Those rules are
 * load-bearing: without a tick size the book fragments into meaningless price levels, and
 * without a minimum notional it fills with dust that costs more to match than it is worth.
 */
@Entity
@Table(name = "spot_symbols")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    @Setter(AccessLevel.NONE)
    private String symbol;

    /** Plain columns — these point into the wallet domain, which takes no foreign key. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long baseAssetId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long quoteAssetId;

    /** Where this market's commands go on the commands topic. Chosen at listing and never
     *  written again: the engine's book and event sequence live under it, so a move would
     *  strand the book and make settlement skip new trades as duplicates. */
    @Column(nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private int commandPartition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SymbolStatus status = SymbolStatus.PRE_TRADING;

    /** All scaled by 1e8. The tick and the step below are fixed at listing, like the partition:
     *  resting orders and every client's rounding were made to them. */
    @Column(nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private long priceTick;

    /**
     * The order-book grouping options a client may choose from: ascending 1e8-scaled
     * values, comma separated, or null to derive them from {@link #priceTick}.
     *
     * <p>Text rather than a JSON array so the only way to read a value is
     * {@code Long.parseLong} -- JSON would store these as JSON numbers, and every layer
     * that then touched them would have a path where money became a double. See V4__spot.sql.
     *
     * <p>Null is "not configured", never "none available". A market listed without this
     * still gets a working selector; see {@code PriceGrouping.derivedFrom}.
     */
    @Column(length = 255)
    private String depthGroupings;

    @Column(nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private long qtyStep;

    @Column(nullable = false)
    private long minQty;

    @Column(nullable = false)
    private long maxQty;

    /**
     * Separate bounds for market orders, because a market order sweeps. A limit order at a
     * bad price simply never fills; an oversized market order walks down through worse and
     * worse levels and fills at an average the user never intended.
     *
     * <p>Null means "no separate rule" — fall back to the limit-order bounds.
     */
    private Long marketMinQty;

    private Long marketMaxQty;

    private Long marketQtyStep;

    @Column(nullable = false)
    private long minNotional;

    private Long maxNotional;

    /** For a market buy sized in the quote asset, where the base quantity is unknown. */
    private Long minQuoteQty;

    private Long maxQuoteQty;

    /** The fat-finger guard: how far from the last price an order may sit, in bps. */
    private Integer priceBandBps;

    /** Per user, per market. Stops one account filling the book with resting orders.
     *  A user with a {@code SpotSymbolUserOrderLimitOverride} row for this symbol is bound
     *  by that instead. */
    @Column(nullable = false)
    private int maxOpenOrders = 200;

    /** Per user, per market. A pile of conditional orders parked at the same stop price
     *  all fire together in one synchronous burst the instant it prints -- a tighter cap
     *  than {@link #maxOpenOrders} because that burst, not just memory, is the risk.
     *  Also overridable per user, see {@link #maxOpenOrders}. */
    @Column(nullable = false)
    private int maxOpenConditionalOrders = 50;

    @Column(nullable = false)
    private boolean marketOrdersEnabled = true;

    @Column(nullable = false)
    private boolean stopOrdersEnabled = false;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public SpotSymbol(String symbol, Long baseAssetId, Long quoteAssetId,
                      long priceTick, long qtyStep, long minQty, long maxQty, long minNotional,
                      int commandPartition) {
        this.symbol = symbol;
        this.commandPartition = commandPartition;
        this.baseAssetId = baseAssetId;
        this.quoteAssetId = quoteAssetId;
        this.priceTick = priceTick;
        this.qtyStep = qtyStep;
        this.minQty = minQty;
        this.maxQty = maxQty;
        this.minNotional = minNotional;
    }

    public boolean acceptsNewOrders() {
        return status == SymbolStatus.TRADING;
    }

    public boolean isPriceOnTick(long price) {
        return price > 0 && price % priceTick == 0;
    }

    /** Limit-order bounds. For a market order use {@link #isValidQuantity}. */
    public boolean isQuantityOnStep(long quantity) {
        return quantity >= minQty && quantity <= maxQty && quantity % qtyStep == 0;
    }

    /**
     * Bounds for the given order type, falling back to the limit-order rule where no
     * market-specific one is configured.
     */
    public boolean isValidQuantity(boolean isMarketOrder, long quantity) {
        if (!isMarketOrder) {
            return isQuantityOnStep(quantity);
        }
        long min = marketMinQty != null ? marketMinQty : minQty;
        long max = marketMaxQty != null ? marketMaxQty : maxQty;
        long step = marketQtyStep != null ? marketQtyStep : qtyStep;
        return quantity >= min && quantity <= max && quantity % step == 0;
    }

    /** For a market buy sized in the quote asset. No bounds configured means no limit. */
    public boolean isValidQuoteQuantity(long quoteQuantity) {
        if (quoteQuantity <= 0) {
            return false;
        }
        if (minQuoteQty != null && quoteQuantity < minQuoteQty) {
            return false;
        }
        return maxQuoteQty == null || quoteQuantity <= maxQuoteQty;
    }

    /** price * quantity, unscaling once so the result is still 1e8-scaled. */
    public long notionalOf(long price, long quantity) {
        return Money.multiply(price, quantity);
    }

    public boolean meetsNotional(long price, long quantity) {
        long notional = notionalOf(price, quantity);
        return notional >= minNotional && (maxNotional == null || notional <= maxNotional);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotSymbol other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
