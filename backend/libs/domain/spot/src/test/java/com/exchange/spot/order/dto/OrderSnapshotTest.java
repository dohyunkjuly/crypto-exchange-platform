package com.exchange.spot.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.contracts.spot.v1.OrderAccepted;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The acceptance frame's row reads like the database row would before any fill. */
class OrderSnapshotTest {

    private static final long ONE = 100_000_000L;

    @Test
    void anAcceptedStopReadsLikeTheDatabaseRowWouldBeforeAnyFill() {
        OrderAccepted accepted = OrderAccepted.newBuilder()
                .setSide(com.exchange.contracts.spot.v1.Side.SIDE_SELL)
                .setOrderType(com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS_LIMIT)
                .setTimeInForce(com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC)
                .setPrice(59_000 * ONE).setStopPrice(60_000 * ONE)
                .setQuantity(ONE / 2).setPendingTrigger(true).build();
        OrderSnapshot view = OrderSnapshot.accepted("id", "BTCUSDT", accepted, "PENDING_TRIGGER", Instant.EPOCH);
        assertThat(view.orderType()).isEqualTo("STOP_LOSS_LIMIT");
        assertThat(view.side()).isEqualTo("SELL");
        assertThat(view.timeInForce()).isEqualTo("GTC");
        assertThat(view.stopPrice()).isEqualTo("60000");
        assertThat(view.triggerDirection()).isEqualTo("FALL");
        assertThat(view.quantity()).isEqualTo("0.5");
        assertThat(view.remainingQuantity()).isEqualTo("0.5");
        assertThat(view.filledQuantity()).isEqualTo("0");
        assertThat(view.quoteOrderQty()).isNull();
        assertThat(view.trailingDeltaBps()).isNull();
    }

    /** Same rule as {@link OrderSnapshot#of}: a quote-sized order has no base quantity to report. */
    @Test
    void aQuoteSizedStopReportsItsBudgetNotAQuantity() {
        OrderAccepted accepted = OrderAccepted.newBuilder()
                .setSide(com.exchange.contracts.spot.v1.Side.SIDE_BUY)
                .setOrderType(com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS)
                .setStopPrice(70_000 * ONE).setQuantity(500 * ONE).setQuoteOrderQty(500 * ONE)
                .setTrailingDeltaBps(150).setPendingTrigger(true).build();
        OrderSnapshot view = OrderSnapshot.accepted("id", "BTCUSDT", accepted, "PENDING_TRIGGER", Instant.EPOCH);
        assertThat(view.quantity()).isNull();
        assertThat(view.remainingQuantity()).isNull();
        assertThat(view.quoteOrderQty()).isEqualTo("500");
        assertThat(view.trailingDeltaBps()).isEqualTo(150);
        assertThat(view.price()).isNull();
        assertThat(view.timeInForce()).isNull();
    }
}
