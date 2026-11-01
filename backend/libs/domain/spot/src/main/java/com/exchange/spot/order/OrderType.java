package com.exchange.spot.order;

import java.util.Arrays;
import java.util.List;

public enum OrderType {
    LIMIT,
    MARKET,
    STOP_LOSS,
    STOP_LOSS_LIMIT,
    TAKE_PROFIT,
    TAKE_PROFIT_LIMIT,
    /** Rejected outright if it would take, so it can only ever be a maker. */
    LIMIT_MAKER;

    /** Parked in the trigger book until a stop price is reached; counted under the algo-order cap. */
    public boolean isConditional() {
        return this == STOP_LOSS || this == STOP_LOSS_LIMIT || this == TAKE_PROFIT || this == TAKE_PROFIT_LIMIT;
    }

    /** The conditional types as a list, for a repository's {@code In} clause. Derived, so it cannot drift. */
    public static final List<OrderType> CONDITIONAL = Arrays.stream(values()).filter(OrderType::isConditional).toList();
}
