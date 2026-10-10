package com.exchange.spot.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.spot.order.exception.InvalidOrderTypeException;
import com.exchange.spot.order.exception.InvalidSelfTradePreventionModeException;
import com.exchange.spot.order.exception.InvalidSideException;
import com.exchange.spot.order.exception.InvalidTimeInForceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderRequestFieldsTest {

    @Test
    @DisplayName("each field parses its own names and passes null through")
    void valid_values_and_null_parse() {
        assertThat(OrderRequestFields.side("SELL")).isEqualTo(Side.SELL);
        assertThat(OrderRequestFields.orderType("LIMIT_MAKER")).isEqualTo(OrderType.LIMIT_MAKER);
        assertThat(OrderRequestFields.timeInForce("FOK")).isEqualTo(TimeInForce.FOK);
        assertThat(OrderRequestFields.selfTradePreventionMode("EXPIRE_BOTH"))
                .isEqualTo(SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH);

        assertThat(OrderRequestFields.side(null)).isNull();
        assertThat(OrderRequestFields.orderType(null)).isNull();
        assertThat(OrderRequestFields.timeInForce(null)).isNull();
        assertThat(OrderRequestFields.selfTradePreventionMode(null)).isNull();
    }

    @Test
    @DisplayName("an unknown value gets that field's own exception")
    void unknown_values_throw_their_field_exception() {
        assertThatThrownBy(() -> OrderRequestFields.side("FOO")).isInstanceOf(InvalidSideException.class);
        assertThatThrownBy(() -> OrderRequestFields.orderType("FOO")).isInstanceOf(InvalidOrderTypeException.class);
        assertThatThrownBy(() -> OrderRequestFields.timeInForce("FOO")).isInstanceOf(InvalidTimeInForceException.class);
        assertThatThrownBy(() -> OrderRequestFields.selfTradePreventionMode("FOO"))
                .isInstanceOf(InvalidSelfTradePreventionModeException.class);
    }

    @Test
    @DisplayName("names are exact: lowercase is not accepted")
    void names_are_case_sensitive() {
        assertThatThrownBy(() -> OrderRequestFields.side("buy")).isInstanceOf(InvalidSideException.class);
    }

    @Test
    @DisplayName("only the three short names are self-trade prevention modes")
    void non_modes_are_refused() {
        for (String value : new String[] {"NONE", "UNSPECIFIED", "UNRECOGNIZED", "expire_maker",
                "SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER"}) {
            assertThatThrownBy(() -> OrderRequestFields.selfTradePreventionMode(value))
                    .as(value).isInstanceOf(InvalidSelfTradePreventionModeException.class);
        }
    }
}
