package com.exchange.bff.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import org.junit.jupiter.api.Test;

/** The direction flips on side as well as type, so one wrong case would read "≥" for "≤". */
class OrderViewTest {

    @Test
    void aStopLossGuardsAgainstTheMoveThatHurtsThePosition() {
        assertThat(OrderView.triggerDirection(OrderType.STOP_LOSS, Side.SELL)).isEqualTo("FALL");
        assertThat(OrderView.triggerDirection(OrderType.STOP_LOSS_LIMIT, Side.SELL)).isEqualTo("FALL");
        assertThat(OrderView.triggerDirection(OrderType.STOP_LOSS, Side.BUY)).isEqualTo("RISE");
        assertThat(OrderView.triggerDirection(OrderType.STOP_LOSS_LIMIT, Side.BUY)).isEqualTo("RISE");
    }

    @Test
    void aTakeProfitWaitsForTheMoveThatHelpsIt() {
        assertThat(OrderView.triggerDirection(OrderType.TAKE_PROFIT, Side.SELL)).isEqualTo("RISE");
        assertThat(OrderView.triggerDirection(OrderType.TAKE_PROFIT_LIMIT, Side.SELL)).isEqualTo("RISE");
        assertThat(OrderView.triggerDirection(OrderType.TAKE_PROFIT, Side.BUY)).isEqualTo("FALL");
        assertThat(OrderView.triggerDirection(OrderType.TAKE_PROFIT_LIMIT, Side.BUY)).isEqualTo("FALL");
    }

    @Test
    void anUntriggeredTypeHasNoDirection() {
        for (OrderType type : new OrderType[] {OrderType.LIMIT, OrderType.MARKET, OrderType.LIMIT_MAKER}) {
            assertThat(OrderView.triggerDirection(type, Side.BUY)).isNull();
        }
    }
}
