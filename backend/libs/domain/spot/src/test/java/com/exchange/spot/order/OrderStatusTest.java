package com.exchange.spot.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    /** A status added later must land in one of the two lists, or every open-order count is wrong. */
    @Test
    void openIsExactlyWhatIsNotTerminal() {
        assertThat(OrderStatus.OPEN).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(OrderStatus.values()).filter(s -> !s.isTerminal()).toList());
    }

    @Test
    void cancelableIsOpenWithoutACancelAlreadyOnItsWay() {
        assertThat(OrderStatus.CANCELABLE).containsExactlyInAnyOrderElementsOf(
                OrderStatus.OPEN.stream().filter(s -> s != OrderStatus.PENDING_CANCEL).toList());
    }
}
