package com.exchange.spot.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpotOrderTest {

    @Test
    void applyFill_onPlainQuantityOrder_accumulatesAndTransitionsToPartiallyFilled() {
        SpotOrder order = limitBuy(1_000_000_000L); // 10.0

        order.applyFill(300_000_000L, 600_000_000L);

        assertThat(order.getFilledQuantity()).isEqualTo(300_000_000L);
        assertThat(order.getCumulativeQuoteQuantity()).isEqualTo(600_000_000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void applyFill_onPlainQuantityOrder_transitionsToFilledWhenFullyFilled() {
        SpotOrder order = limitBuy(1_000_000_000L); // 10.0

        order.applyFill(1_000_000_000L, 2_000_000_000L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void applyFill_onPlainQuantityOrder_rejectsOverfill() {
        SpotOrder order = limitBuy(1_000_000_000L); // 10.0

        assertThatThrownBy(() -> order.applyFill(1_000_000_001L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds remaining");
    }

    @Test
    void applyFill_onQuoteOrderQtyOrder_doesNotCrashOnFirstFill() {
        // Regression test: quantity stays 0 for the order's whole life on a
        // quoteOrderQty order, so remainingQuantity() would go negative and the
        // plain-quantity overfill guard would reject every fill of one.
        SpotOrder order = marketBuyByQuote(1_000_000_000L); // $10 budget

        order.applyFill(150_000_000L, 900_000_000L);

        assertThat(order.getFilledQuantity()).isEqualTo(150_000_000L);
        assertThat(order.getCumulativeQuoteQuantity()).isEqualTo(900_000_000L);
    }

    @Test
    void applyFill_onQuoteOrderQtyOrder_acceptsFillsUpToTheQuoteBudget() {
        SpotOrder order = marketBuyByQuote(1_000_000_000L); // $10 budget

        order.applyFill(50_000_000L, 400_000_000L);
        order.applyFill(70_000_000L, 600_000_000L); // cumulative quote == budget exactly

        assertThat(order.getCumulativeQuoteQuantity()).isEqualTo(1_000_000_000L);
    }

    @Test
    void applyFill_onQuoteOrderQtyOrder_rejectsAFillThatExceedsTheRemainingQuoteBudget() {
        SpotOrder order = marketBuyByQuote(1_000_000_000L); // $10 budget
        order.applyFill(50_000_000L, 400_000_000L);

        assertThatThrownBy(() -> order.applyFill(80_000_000L, 700_000_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds remaining quote budget");
    }

    private static SpotOrder limitBuy(long quantity) {
        return new SpotOrder(UUID.randomUUID(), 1L, 1L, Side.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 6_325_000_000_000L, null, quantity, null, 10, 10, null, null);
    }

    private static SpotOrder marketBuyByQuote(long quoteOrderQty) {
        return new SpotOrder(UUID.randomUUID(), 1L, 1L, Side.BUY, OrderType.MARKET,
                TimeInForce.IOC, null, null, 0L, quoteOrderQty, 10, 10, null, null);
    }
}
