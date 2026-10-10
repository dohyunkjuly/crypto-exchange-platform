package com.exchange.bff.order;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.spot.history.InvalidTimeRangeException;
import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.MarketUnavailableException;
import com.exchange.spot.order.exception.FokWithExpireBothNotSupportedException;
import com.exchange.spot.order.exception.InvalidOrderTypeException;
import com.exchange.spot.order.exception.InvalidSelfTradePreventionModeException;
import com.exchange.spot.order.exception.InvalidSideException;
import com.exchange.spot.order.exception.InvalidTimeInForceException;
import com.exchange.spot.order.exception.AlgoOrderLimitExceededException;
import com.exchange.spot.order.exception.InvalidPriceException;
import com.exchange.spot.order.exception.InvalidQuantityException;
import com.exchange.spot.order.exception.InvalidStopPriceException;
import com.exchange.spot.order.exception.InvalidTrailingDeltaException;
import com.exchange.spot.order.exception.ManualOrderNotAllowedException;
import com.exchange.spot.order.exception.MarketNotAllowedException;
import com.exchange.spot.order.exception.MarketOrdersDisabledException;
import com.exchange.spot.order.exception.NotionalOutOfRangeException;
import com.exchange.spot.order.exception.OrderLimitExceededException;
import com.exchange.spot.order.exception.OrderNotCancelableException;
import com.exchange.spot.order.exception.OrderNotFoundException;
import com.exchange.spot.order.exception.QuoteOrderQtyNotSupportedException;
import com.exchange.spot.order.exception.QuoteOrderQtyRequiredException;
import com.exchange.spot.order.exception.StopOrdersDisabledException;
import com.exchange.spot.order.exception.SymbolNotFoundException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.order.exception.TimeInForceNotSupportedException;
import com.exchange.spot.order.exception.OrderNotAmendableException;
import com.exchange.spot.order.exception.AmendPendingException;
import com.exchange.spot.order.exception.NothingToAmendException;
import com.exchange.spot.order.exception.FieldNotAmendableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code OrderService}'s plain domain exceptions to this app's own
 * {@link BffHttpErrorCode}. That service lives in {@code libs/domain/spot} because
 * spot-open-api needs it too, and can't depend on either app's error codes -- so each
 * app owns this translation for itself, the same split {@code ApiKeyExceptionAdvice}
 * already establishes for {@code ApiKeyService}. An equivalent small advice in
 * spot-open-api does the same with its own codes.
 */
@RestControllerAdvice
public class OrderExceptionAdvice {

    @ExceptionHandler(SymbolNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleSymbolNotFound(SymbolNotFoundException e) {
        return respond(BffHttpErrorCode.SYMBOL_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SymbolNotTradingException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleSymbolNotTrading(
            SymbolNotTradingException e) {
        return respond(BffHttpErrorCode.SYMBOL_NOT_TRADING, e.getMessage());
    }

    @ExceptionHandler(MarketOrdersDisabledException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMarketOrdersDisabled(
            MarketOrdersDisabledException e) {
        return respond(BffHttpErrorCode.MARKET_ORDERS_DISABLED, e.getMessage());
    }

    @ExceptionHandler(StopOrdersDisabledException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleStopOrdersDisabled(
            StopOrdersDisabledException e) {
        return respond(BffHttpErrorCode.STOP_ORDERS_DISABLED, e.getMessage());
    }

    @ExceptionHandler(TimeInForceNotSupportedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleTimeInForceNotSupported(
            TimeInForceNotSupportedException e) {
        return respond(BffHttpErrorCode.TIME_IN_FORCE_NOT_SUPPORTED, e.getMessage());
    }

    @ExceptionHandler(InvalidPriceException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidPrice(InvalidPriceException e) {
        return respond(BffHttpErrorCode.INVALID_PRICE, e.getMessage());
    }

    @ExceptionHandler(InvalidStopPriceException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidStopPrice(
            InvalidStopPriceException e) {
        return respond(BffHttpErrorCode.INVALID_STOP_PRICE, e.getMessage());
    }

    @ExceptionHandler(InvalidQuantityException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidQuantity(
            InvalidQuantityException e) {
        return respond(BffHttpErrorCode.INVALID_QUANTITY, e.getMessage());
    }

    @ExceptionHandler(QuoteOrderQtyRequiredException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleQuoteOrderQtyRequired(
            QuoteOrderQtyRequiredException e) {
        return respond(BffHttpErrorCode.QUOTE_ORDER_QTY_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(QuoteOrderQtyNotSupportedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleQuoteOrderQtyNotSupported(
            QuoteOrderQtyNotSupportedException e) {
        return respond(BffHttpErrorCode.QUOTE_ORDER_QTY_NOT_SUPPORTED, e.getMessage());
    }

    @ExceptionHandler(InvalidTrailingDeltaException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidTrailingDelta(
            InvalidTrailingDeltaException e) {
        return respond(BffHttpErrorCode.INVALID_TRAILING_DELTA, e.getMessage());
    }

    @ExceptionHandler(NotionalOutOfRangeException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleNotionalOutOfRange(
            NotionalOutOfRangeException e) {
        return respond(BffHttpErrorCode.NOTIONAL_OUT_OF_RANGE, e.getMessage());
    }

    @ExceptionHandler(OrderLimitExceededException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleOrderLimitExceeded(
            OrderLimitExceededException e) {
        return respond(BffHttpErrorCode.ORDER_LIMIT_EXCEEDED, e.getMessage());
    }

    @ExceptionHandler(AlgoOrderLimitExceededException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleAlgoOrderLimitExceeded(
            AlgoOrderLimitExceededException e) {
        return respond(BffHttpErrorCode.ALGO_ORDER_LIMIT_EXCEEDED, e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleOrderNotFound(OrderNotFoundException e) {
        return respond(BffHttpErrorCode.ORDER_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(OrderNotCancelableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleOrderNotCancelable(
            OrderNotCancelableException e) {
        return respond(BffHttpErrorCode.ORDER_NOT_CANCELABLE, e.getMessage());
    }

    @ExceptionHandler(FokWithExpireBothNotSupportedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleFokWithExpireBoth(
            FokWithExpireBothNotSupportedException e) {
        return respond(BffHttpErrorCode.FOK_EXPIRE_BOTH_NOT_SUPPORTED, e.getMessage());
    }

    @ExceptionHandler(InvalidSideException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidSide(InvalidSideException e) {
        return respond(BffHttpErrorCode.INVALID_SIDE, e.getMessage());
    }

    @ExceptionHandler(InvalidOrderTypeException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidOrderType(InvalidOrderTypeException e) {
        return respond(BffHttpErrorCode.INVALID_ORDER_TYPE, e.getMessage());
    }

    @ExceptionHandler(InvalidTimeInForceException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidTimeInForce(InvalidTimeInForceException e) {
        return respond(BffHttpErrorCode.INVALID_TIME_IN_FORCE, e.getMessage());
    }

    @ExceptionHandler(InvalidSelfTradePreventionModeException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidSelfTradePreventionMode(
            InvalidSelfTradePreventionModeException e) {
        return respond(BffHttpErrorCode.INVALID_SELF_TRADE_PREVENTION_MODE, e.getMessage());
    }

    @ExceptionHandler(OrderNotAmendableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleOrderNotAmendable(OrderNotAmendableException e) {
        return respond(BffHttpErrorCode.ORDER_NOT_AMENDABLE, e.getMessage());
    }

    @ExceptionHandler(AmendPendingException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleAmendPending(AmendPendingException e) {
        return respond(BffHttpErrorCode.AMEND_PENDING, e.getMessage());
    }

    @ExceptionHandler(NothingToAmendException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleNothingToAmend(NothingToAmendException e) {
        return respond(BffHttpErrorCode.NOTHING_TO_AMEND, e.getMessage());
    }

    @ExceptionHandler(FieldNotAmendableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleFieldNotAmendable(FieldNotAmendableException e) {
        return respond(BffHttpErrorCode.FIELD_NOT_AMENDABLE, e.getMessage());
    }

    @ExceptionHandler(ManualOrderNotAllowedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleManualOrderNotAllowed(ManualOrderNotAllowedException e) {
        return respond(BffHttpErrorCode.COPY_ACCOUNT_NOT_TRADABLE, e.getMessage());
    }

    @ExceptionHandler(MarketNotAllowedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMarketNotAllowed(MarketNotAllowedException e) {
        return respond(BffHttpErrorCode.MARKET_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(MarketUnavailableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMarketUnavailable(MarketUnavailableException e) {
        return respond(BffHttpErrorCode.MARKET_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(EngineUnavailableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleEngineUnavailable(EngineUnavailableException e) {
        return respond(BffHttpErrorCode.MARKET_PAUSED, e.getMessage());
    }

    @ExceptionHandler(InvalidTimeRangeException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidTimeRange(InvalidTimeRangeException e) {
        return respond(BffHttpErrorCode.INVALID_TIME_RANGE, e.getMessage());
    }

    private ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
