package com.exchange.spot.copy.copy;

import com.exchange.common.money.Money;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.symbol.SpotSymbol;
import java.math.BigInteger;

/**
 * What one follower's copy of one closed lead order is (docs/reference/copy-trading.md). Pure
 * arithmetic: everything it needs is passed in, so every rule here has a plain test.
 *
 * <p><b>A buy</b> is a limit order that fills at once or not at all, priced at the leader's
 * average fill plus the slippage cap, on the tick: the follower gets the best prices up to the
 * cap and never beyond it. Its cost is the leader's share of their available quote applied to
 * the follower's, or the follower's fixed amount.
 *
 * <p><b>A sell</b> is a market order for the share of the holding the leader sold, applied to
 * what the copy account holds. It has no cap: a follower must not be left holding what the
 * leader has left.
 *
 * <p>A lead order that filled in part is copied in that part.
 */
public final class CopySizing {

    private CopySizing() {
    }

    /** What to do for this follower. */
    public sealed interface Plan permits Place, Skip, Fail {
    }

    /** @param price null for a market order */
    public record Place(Side side, OrderType type, TimeInForce timeInForce, Long price, long quantity) implements Plan {
    }

    /** Nothing to do, and nothing went wrong. */
    public record Skip(CopyOrderReason reason) implements Plan {
    }

    /** Cannot be copied: counts towards closing the account. */
    public record Fail(CopyOrderReason reason) implements Plan {
    }

    /**
     * @param quoteAvailable the copy account's available quote asset
     * @param baseHeld       what it holds of the market's base asset, available to sell
     * @param capBps         how far above the leader's price a buy may fill
     */
    public static Plan plan(CopyFollow follow, CopyLeadOrder lead, SpotSymbol market, long quoteAvailable,
                            long baseHeld, int capBps) {
        long share = share(lead);
        return lead.getSide() == Side.BUY
                ? buy(follow, lead, market, quoteAvailable, share, capBps)
                : sell(market, baseHeld, share);
    }

    /** The lead order's fraction, scaled by how much of the order filled. Never above all of it. */
    static long share(CopyLeadOrder lead) {
        return BigInteger.valueOf(lead.getFraction()).multiply(BigInteger.valueOf(lead.filledAmount()))
                .divide(BigInteger.valueOf(lead.getAmount())).min(BigInteger.valueOf(CopyLeadOrder.WHOLE))
                .longValueExact();
    }

    private static Plan buy(CopyFollow follow, CopyLeadOrder lead, SpotSymbol market, long quoteAvailable,
                            long share, int capBps) {
        if (quoteAvailable <= 0) {
            return new Fail(CopyOrderReason.INSUFFICIENT_BALANCE);
        }
        long cost = follow.getMode() == CopyMode.FIXED_RATIO
                ? portion(quoteAvailable, share)
                : Math.min(follow.getAmountPerOrder(), quoteAvailable);

        long average = Money.divide(lead.getFilledQuoteQuantity(), lead.getFilledQuantity());
        long capped = average + Money.applyBps(average, capBps);
        long price = capped - capped % market.getPriceTick();
        if (price <= 0) {
            return new Fail(CopyOrderReason.BELOW_MINIMUM);
        }
        // Down to the step: the order must never cost more than what was set aside for it.
        long quantity = Money.divide(cost, price);
        quantity -= quantity % market.getQtyStep();
        if (!market.isValidQuantity(false, quantity) || !market.meetsNotional(price, quantity)) {
            return new Fail(CopyOrderReason.BELOW_MINIMUM);
        }
        return new Place(Side.BUY, OrderType.LIMIT, TimeInForce.IOC, price, quantity);
    }

    private static Plan sell(SpotSymbol market, long baseHeld, long share) {
        if (baseHeld <= 0) {
            return new Skip(CopyOrderReason.NOTHING_TO_SELL);
        }
        long step = market.getMarketQtyStep() != null ? market.getMarketQtyStep() : market.getQtyStep();
        long quantity = portion(baseHeld, share);
        quantity -= quantity % step;
        if (!market.isValidQuantity(true, quantity)) {
            return new Fail(CopyOrderReason.BELOW_MINIMUM);
        }
        return new Place(Side.SELL, OrderType.MARKET, null, null, quantity);
    }

    /** {@code share} of {@code amount}, rounded down; all of it is exactly all of it. */
    private static long portion(long amount, long share) {
        if (share >= CopyLeadOrder.WHOLE) {
            return amount;
        }
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(share))
                .divide(BigInteger.valueOf(CopyLeadOrder.WHOLE)).longValueExact();
    }
}
