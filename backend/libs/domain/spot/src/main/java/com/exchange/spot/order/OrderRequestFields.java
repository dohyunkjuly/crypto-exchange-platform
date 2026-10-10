package com.exchange.spot.order;

import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.spot.order.exception.InvalidOrderTypeException;
import com.exchange.spot.order.exception.InvalidSelfTradePreventionModeException;
import com.exchange.spot.order.exception.InvalidSideException;
import com.exchange.spot.order.exception.InvalidTimeInForceException;
import java.util.Map;

/**
 * Parses the enum fields of a place-order request, which both apps bind as strings. A bad
 * value then becomes its own exception, and each app maps it to a dedicated code. Bound as
 * enums, Jackson would fail first with one generic error for all of them.
 *
 * <p>Null passes through: whether a field is required is {@code @NotBlank}'s or
 * {@link OrderService}'s decision, not this parser's.
 */
public final class OrderRequestFields {

    /** Short names on the wire, like every other field. The proto's prefixed names, its
     *  UNSPECIFIED and protobuf's own UNRECOGNIZED are not accepted. */
    private static final Map<String, SelfTradePreventionMode> MODES = Map.of(
            "EXPIRE_TAKER", SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER,
            "EXPIRE_MAKER", SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER,
            "EXPIRE_BOTH", SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH);

    private OrderRequestFields() {
    }

    public static Side side(String value) {
        try {
            return value == null ? null : Side.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSideException(value);
        }
    }

    public static OrderType orderType(String value) {
        try {
            return value == null ? null : OrderType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderTypeException(value);
        }
    }

    public static TimeInForce timeInForce(String value) {
        try {
            return value == null ? null : TimeInForce.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidTimeInForceException(value);
        }
    }

    public static SelfTradePreventionMode selfTradePreventionMode(String value) {
        if (value == null) {
            return null;
        }
        SelfTradePreventionMode mode = MODES.get(value);
        if (mode == null) {
            throw new InvalidSelfTradePreventionModeException(value);
        }
        return mode;
    }
}
