package com.exchange.spot.copy.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.spot.copy.copy.CopySizing.Fail;
import com.exchange.spot.copy.copy.CopySizing.Place;
import com.exchange.spot.copy.copy.CopySizing.Skip;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.symbol.SpotSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a follower's copy is: how much, at what price, and when there is none. */
class CopySizingTest {

    private static final long ONE = 100_000_000L;
    private static final int CAP_BPS = 50;

    // Tick 0.01, step 0.001, minimum 0.001, minimum notional 10.
    private final SpotSymbol market = new SpotSymbol("BTCUSDT", 1L, 2L, 1_000_000L, 100_000L, 100_000L,
            1_000 * ONE, 10 * ONE, 0);
    private final CopyFollow ratio = follow(CopyMode.FIXED_RATIO, null);
    private final CopyFollow fixed = follow(CopyMode.FIXED_AMOUNT, 20 * ONE);

    @Test
    @DisplayName("fixed ratio: the follower spends the share of their available quote the leader spent of theirs")
    void fixedRatioBuy() {
        // The leader spent half their USDT, buying 5 at 100.
        CopyLeadOrder lead = filled(buy(CopyLeadOrder.WHOLE / 2, 500 * ONE), 5 * ONE, 500 * ONE);

        Place copy = (Place) CopySizing.plan(ratio, lead, market, 1_000 * ONE, 0, CAP_BPS);

        assertThat(copy.side()).isEqualTo(Side.BUY);
        assertThat(copy.type()).isEqualTo(OrderType.LIMIT);
        assertThat(copy.timeInForce()).as("fills at once or not at all").isEqualTo(TimeInForce.IOC);
        assertThat(copy.price()).as("100 plus the 0.5% cap").isEqualTo(100_50_000_000L);
        // 500 / 100.50 = 4.9751..., down to the 0.001 step.
        assertThat(copy.quantity()).isEqualTo(4_975 * (ONE / 1_000));
        assertThat(market.notionalOf(copy.price(), copy.quantity())).as("never more than was set aside")
                .isLessThanOrEqualTo(500 * ONE);
    }

    @Test
    @DisplayName("the cap goes down onto the tick, never up past it")
    void capOnTheTick() {
        // Average 100.333: plus 0.5% is 100.834665, onto the 0.01 tick is 100.83.
        CopyLeadOrder lead = filled(buy(CopyLeadOrder.WHOLE, 30_099_900_000L), 3 * ONE, 30_099_900_000L);

        Place copy = (Place) CopySizing.plan(ratio, lead, market, 1_000 * ONE, 0, CAP_BPS);

        assertThat(copy.price()).isEqualTo(100_83_000_000L);
        assertThat(copy.price() % market.getPriceTick()).isZero();
    }

    @Test
    @DisplayName("fixed amount: every buy costs the follower's amount, or what is left if that is less")
    void fixedAmountBuy() {
        CopyLeadOrder lead = filled(buy(CopyLeadOrder.WHOLE / 2, 500 * ONE), 5 * ONE, 500 * ONE);

        Place full = (Place) CopySizing.plan(fixed, lead, market, 1_000 * ONE, 0, CAP_BPS);
        Place rest = (Place) CopySizing.plan(fixed, lead, market, 12 * ONE, 0, CAP_BPS);

        // 20 / 100.50 = 0.199, and 12 / 100.50 = 0.119.
        assertThat(full.quantity()).isEqualTo(199 * (ONE / 1_000));
        assertThat(rest.quantity()).isEqualTo(119 * (ONE / 1_000));
    }

    @Test
    @DisplayName("a lead order that filled in part is copied in that part")
    void partFilled() {
        // Half the leader's USDT was ordered; half of that order filled: a quarter was spent.
        CopyLeadOrder lead = filled(buy(CopyLeadOrder.WHOLE / 2, 500 * ONE), 250 * ONE / 100, 250 * ONE);

        Place copy = (Place) CopySizing.plan(ratio, lead, market, 1_000 * ONE, 0, CAP_BPS);

        // 250 / 100.50 = 2.487.
        assertThat(copy.quantity()).isEqualTo(2_487 * (ONE / 1_000));
    }

    @Test
    @DisplayName("a sell is a market order for the share of the holding the leader sold")
    void sell() {
        CopyLeadOrder lead = filled(sell(CopyLeadOrder.WHOLE / 5, ONE / 5), ONE / 5, 20 * ONE);

        Place copy = (Place) CopySizing.plan(ratio, lead, market, 0, 6 * ONE / 10, CAP_BPS);

        assertThat(copy.side()).isEqualTo(Side.SELL);
        assertThat(copy.type()).isEqualTo(OrderType.MARKET);
        assertThat(copy.price()).as("no cap on the way out").isNull();
        assertThat(copy.quantity()).as("20% of 0.6").isEqualTo(12 * ONE / 100);
    }

    @Test
    @DisplayName("a full exit sells everything held, down to the step")
    void fullExit() {
        CopyLeadOrder lead = filled(sell(CopyLeadOrder.WHOLE, ONE), ONE, 100 * ONE);

        Place copy = (Place) CopySizing.plan(ratio, lead, market, 0, 12_345_678L, CAP_BPS);

        assertThat(copy.quantity()).isEqualTo(12_300_000L);
    }

    @Test
    @DisplayName("a follower who missed the buy has nothing to sell: skipped, not failed")
    void nothingToSell() {
        CopyLeadOrder lead = filled(sell(CopyLeadOrder.WHOLE, ONE), ONE, 100 * ONE);

        assertThat(CopySizing.plan(ratio, lead, market, 500 * ONE, 0, CAP_BPS))
                .isEqualTo(new Skip(CopyOrderReason.NOTHING_TO_SELL));
    }

    @Test
    @DisplayName("no quote to spend, or a copy smaller than the market takes, is a failed copy")
    void failures() {
        CopyLeadOrder buy = filled(buy(CopyLeadOrder.WHOLE / 2, 500 * ONE), 5 * ONE, 500 * ONE);
        assertThat(CopySizing.plan(ratio, buy, market, 0, 0, CAP_BPS))
                .isEqualTo(new Fail(CopyOrderReason.INSUFFICIENT_BALANCE));
        // Half of 12 USDT is 6: under the market's minimum notional of 10.
        assertThat(CopySizing.plan(ratio, buy, market, 12 * ONE, 0, CAP_BPS))
                .isEqualTo(new Fail(CopyOrderReason.BELOW_MINIMUM));
        // 20% of 0.002 is 0.0004: under the 0.001 step.
        CopyLeadOrder sell = filled(sell(CopyLeadOrder.WHOLE / 5, ONE / 5), ONE / 5, 20 * ONE);
        assertThat(CopySizing.plan(ratio, sell, market, 0, 200_000L, CAP_BPS))
                .isEqualTo(new Fail(CopyOrderReason.BELOW_MINIMUM));
    }

    private static CopyFollow follow(CopyMode mode, Long amountPerOrder) {
        return new CopyFollow(Ulid.randomUuid(), Ulid.randomUuid(), 1L, 2L, mode, amountPerOrder, 1000);
    }

    private static CopyLeadOrder buy(long fraction, long cost) {
        return new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.BUY, fraction, cost, 0);
    }

    private static CopyLeadOrder sell(long fraction, long quantity) {
        return new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.SELL, fraction, quantity, quantity);
    }

    private static CopyLeadOrder filled(CopyLeadOrder order, long quantity, long quote) {
        order.addFill(1, quantity, quote);
        return order;
    }
}
