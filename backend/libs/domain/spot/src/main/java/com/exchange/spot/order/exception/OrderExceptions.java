package com.exchange.spot.order.exception;

import com.exchange.spot.order.OrderStatus;
import java.util.UUID;

/**
 * Every way placing, amending and cancelling an order can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class OrderExceptions {

    private OrderExceptions() {
    }

    /** The account-wide or per-symbol open-CONDITIONAL-order cap
     *  (SpotExchangeOrderLimits.maxNumAlgoOrders / SpotSymbol.maxOpenConditionalOrders, or
     *  a user's override of either) is already at or over its limit -- a separate, tighter
     *  cap than {@link OrderLimitExceededException}'s. */
    public static class AlgoOrderLimitExceededException extends RuntimeException {

        public AlgoOrderLimitExceededException() {
            super("Too many open conditional orders");
        }
    }

    /** A second amend while the first is still on its way to the engine. One at a time: see
     *  V4__spot.sql for how two computed from the same stale row leave an order under-locked. */
    public static class AmendPendingException extends RuntimeException {

        public AmendPendingException() {
            super("an amend of this order is still being processed");
        }
    }

    /** No matching engine has announced this market's partition recently
     *  ({@code EnginePresence}), so a new order would only queue. Cancelling is never refused. */
    public static class EngineUnavailableException extends RuntimeException {

        public EngineUnavailableException() {
            super("Trading on this market is paused");
        }
    }

    /** The field exists but cannot change on this order: docs/reference/order-types.md has the
     *  table. A trailing order takes a new size only, as everywhere. */
    public static class FieldNotAmendableException extends RuntimeException {

        public FieldNotAmendableException(String field, String why) {
            super(field + " cannot be amended: " + why);
        }
    }

    /** FOK with EXPIRE_BOTH. FOK must leave the book untouched when it cannot fill, and
     *  EXPIRE_BOTH asks to cancel the resting order, so the two contradict each other. */
    public static class FokWithExpireBothNotSupportedException extends RuntimeException {

        public FokWithExpireBothNotSupportedException() {
            super("EXPIRE_BOTH cannot be combined with FOK");
        }
    }

    /** {@code orderType} is not one of {@link com.exchange.spot.order.OrderType}'s names. */
    public static class InvalidOrderTypeException extends RuntimeException {

        public InvalidOrderTypeException(String value) {
            super("orderType must be one of LIMIT, MARKET, STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, "
                    + "TAKE_PROFIT_LIMIT or LIMIT_MAKER, got '" + value + "'");
        }
    }

    /** Missing where required, or not on the symbol's tick size. */
    public static class InvalidPriceException extends RuntimeException {

        public InvalidPriceException(String reason) {
            super(reason);
        }
    }

    /** Missing where required, off the symbol's step size, or outside its bounds --
     *  covers both a base {@code quantity} and a {@code quoteOrderQty}. */
    public static class InvalidQuantityException extends RuntimeException {

        public InvalidQuantityException(String reason) {
            super(reason);
        }
    }

    /** {@code selfTradePreventionMode} is not one of the three modes. There is no mode that
     *  allows a self-trade: settlement records a trade as one buyer and one seller. */
    public static class InvalidSelfTradePreventionModeException extends RuntimeException {

        public InvalidSelfTradePreventionModeException(String value) {
            super("selfTradePreventionMode must be one of EXPIRE_TAKER, EXPIRE_MAKER or EXPIRE_BOTH, "
                    + "got '" + value + "'");
        }
    }

    /** {@code side} is not one of {@code BUY} or {@code SELL}. */
    public static class InvalidSideException extends RuntimeException {

        public InvalidSideException(String value) {
            super("side must be BUY or SELL, got '" + value + "'");
        }
    }

    /** Missing on a conditional order that isn't trailing (it's the only field that can
     *  stand in for a required stop price), or not on the symbol's tick size. */
    public static class InvalidStopPriceException extends RuntimeException {

        public InvalidStopPriceException(String reason) {
            super(reason);
        }
    }

    /** {@code timeInForce} is not one of {@link com.exchange.spot.order.TimeInForce}'s names. A
     *  known but unsupported one is {@link TimeInForceNotSupportedException} instead. */
    public static class InvalidTimeInForceException extends RuntimeException {

        public InvalidTimeInForceException(String value) {
            super("timeInForce must be one of GTC, IOC or FOK, got '" + value + "'");
        }
    }

    /** {@code trailingDeltaBps} outside [10, 2000] (same bounds the matching engine
     *  enforces), or set on a non-conditional order. */
    public static class InvalidTrailingDeltaException extends RuntimeException {

        public InvalidTrailingDeltaException() {
            super("trailingDeltaBps must be between 10 and 2000, and only on a conditional order");
        }
    }

    /** The account is traded for its owner, not by them: a copy account (docs/reference/copy-trading.md). */
    public static class ManualOrderNotAllowedException extends RuntimeException {

        public ManualOrderNotAllowedException(String message) {
            super(message);
        }
    }

    /** The market is open, but not to this account: a lead account outside the copyable markets. */
    public static class MarketNotAllowedException extends RuntimeException {

        public MarketNotAllowedException(String message) {
            super(message);
        }
    }

    /** {@code SpotSymbol.marketOrdersEnabled} is false for this symbol. */
    public static class MarketOrdersDisabledException extends RuntimeException {

        public MarketOrdersDisabledException() {
            super("Market orders are disabled for this symbol");
        }
    }

    /** The market's command partition does not exist on the commands topic, so no order can be
     *  published for it. An operator error in {@code spot_symbols.command_partition}, not a user one. */
    public static class MarketUnavailableException extends RuntimeException {

        public MarketUnavailableException(String symbol) {
            super(symbol + " is not available right now");
        }
    }

    /** No field was sent, or every one sent already has that value. */
    public static class NothingToAmendException extends RuntimeException {

        public NothingToAmendException() {
            super("the amend changes nothing");
        }
    }

    /** {@code price * quantity} is outside the symbol's allowed notional range. */
    public static class NotionalOutOfRangeException extends RuntimeException {

        public NotionalOutOfRangeException() {
            super("Order notional is out of the symbol's allowed range");
        }
    }

    /** The account-wide or per-symbol open-order cap (SpotExchangeOrderLimits.maxNumOrders
     *  / SpotSymbol.maxOpenOrders, or a user's override of either) is already at or over
     *  its limit. See {@link AlgoOrderLimitExceededException} for the narrower,
     *  conditional-orders-only cap. */
    public static class OrderLimitExceededException extends RuntimeException {

        public OrderLimitExceededException() {
            super("Too many open orders");
        }
    }

    /** Only an order still waiting can change: resting GTC on the book, or parked on its trigger.
     *  A terminal order, a market order and an IOC or FOK one have nothing left to amend. */
    public static class OrderNotAmendableException extends RuntimeException {

        public OrderNotAmendableException(String reason) {
            super(reason);
        }
    }

    /**
     * The order has already reached a terminal state, so there is nothing left to cancel.
     *
     * <p>Note this is only the check the API can make from its own copy of the order. It is
     * not a guarantee: an order that looks open here may fill in the engine a millisecond
     * later, before the cancel command arrives. That race is resolved by the engine, which
     * simply ignores a cancel for an order it no longer holds -- see {@code
     * MatchingEngine.cancelOrder}. This exception exists to reject the obviously-pointless
     * case early, not to make cancellation synchronous.
     */
    public static class OrderNotCancelableException extends RuntimeException {

        public OrderNotCancelableException(OrderStatus status) {
            super("Order is already " + status + " and cannot be canceled");
        }
    }

    /**
     * No such order <b>for this user</b>.
     *
     * <p>Deliberately the same answer for "does not exist" and "belongs to somebody else":
     * telling a caller that an order id exists but is not theirs confirms the id is real,
     * which is exactly what an attacker enumerating ids is trying to learn. Order ids are
     * ULIDs, so they are guessable in bulk by timestamp prefix -- this is not a theoretical
     * concern.
     */
    public static class OrderNotFoundException extends RuntimeException {

        /** By a client's own order id, which is a string the client chose. */
        public OrderNotFoundException(String clientOrderId) {
            super("No order with clientOrderId " + clientOrderId);
        }

        public OrderNotFoundException(UUID orderId) {
            super("No such order: " + orderId);
        }
    }

    /** {@code quoteOrderQty} given on a SELL, or on any order with a fixed price -- both
     *  have an unambiguous {@code quantity} instead. See docs/reference/order-types.md. */
    public static class QuoteOrderQtyNotSupportedException extends RuntimeException {

        public QuoteOrderQtyNotSupportedException() {
            super("quoteOrderQty is not valid for this order");
        }
    }

    /** A BUY on a MARKET-firing order (MARKET, STOP_LOSS, TAKE_PROFIT) must lock a quote
     *  budget, since its execution price -- and so a base quantity's cost -- isn't known
     *  ahead of matching. See docs/reference/order-types.md. */
    public static class QuoteOrderQtyRequiredException extends RuntimeException {

        public QuoteOrderQtyRequiredException() {
            super("A market-firing BUY requires quoteOrderQty");
        }
    }

    /** {@code SpotSymbol.stopOrdersEnabled} is false for this symbol. */
    public static class StopOrdersDisabledException extends RuntimeException {

        public StopOrdersDisabledException() {
            super("Stop/take-profit orders are disabled for this symbol");
        }
    }

    /** No symbol matches the given code. */
    public static class SymbolNotFoundException extends RuntimeException {

        public SymbolNotFoundException(String symbol) {
            super("No such symbol: " + symbol);
        }
    }

    /** The symbol exists but isn't accepting new orders right now (pre-trading, halted,
     *  cancel-only, post-trading, or delisted). */
    public static class SymbolNotTradingException extends RuntimeException {

        public SymbolNotTradingException() {
            super("Symbol is not open for trading");
        }
    }

    /** GTD, or a missing time in force on anything but a MARKET order, which needs none. */
    public static class TimeInForceNotSupportedException extends RuntimeException {

        public TimeInForceNotSupportedException(String reason) {
            super(reason);
        }
    }
}
