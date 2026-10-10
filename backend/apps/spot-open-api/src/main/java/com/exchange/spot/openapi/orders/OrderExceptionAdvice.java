package com.exchange.spot.openapi.orders;

import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.spot.history.InvalidTimeRangeException;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.spot.order.exception.AlgoOrderLimitExceededException;
import com.exchange.spot.order.exception.InvalidPriceException;
import com.exchange.spot.order.exception.InvalidQuantityException;
import com.exchange.spot.order.exception.InvalidStopPriceException;
import com.exchange.spot.order.exception.InvalidTrailingDeltaException;
import com.exchange.spot.order.exception.ManualOrderNotAllowedException;
import com.exchange.spot.order.exception.MarketNotAllowedException;
import com.exchange.spot.order.exception.MarketOrdersDisabledException;
import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.MarketUnavailableException;
import com.exchange.spot.order.exception.NotionalOutOfRangeException;
import com.exchange.spot.order.exception.OrderLimitExceededException;
import com.exchange.spot.order.exception.OrderNotCancelableException;
import com.exchange.spot.order.exception.OrderNotFoundException;
import com.exchange.spot.order.exception.QuoteOrderQtyNotSupportedException;
import com.exchange.spot.order.exception.QuoteOrderQtyRequiredException;
import com.exchange.spot.order.exception.FokWithExpireBothNotSupportedException;
import com.exchange.spot.order.exception.InvalidOrderTypeException;
import com.exchange.spot.order.exception.InvalidSelfTradePreventionModeException;
import com.exchange.spot.order.exception.InvalidSideException;
import com.exchange.spot.order.exception.InvalidTimeInForceException;
import com.exchange.spot.order.exception.StopOrdersDisabledException;
import com.exchange.spot.order.exception.SymbolNotFoundException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.order.exception.TimeInForceNotSupportedException;
import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import com.exchange.spot.order.exception.OrderNotAmendableException;
import com.exchange.spot.order.exception.AmendPendingException;
import com.exchange.spot.order.exception.NothingToAmendException;
import com.exchange.spot.order.exception.FieldNotAmendableException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The order domain's plain exceptions rendered with this app's codes -- the same mapping web-bff's
 * {@code OrderExceptionAdvice} makes, with the same numbers by convention (a shared module owns
 * no error code, AGENTS.md). One table rather than a handler per class, since every entry is
 * "this exception is that code".
 */
@RestControllerAdvice
public class OrderExceptionAdvice {

    private static final Map<Class<? extends RuntimeException>, OpenApiErrorCode> CODES = Map.ofEntries(
            Map.entry(SymbolNotFoundException.class, OpenApiErrorCode.SYMBOL_NOT_FOUND),
            Map.entry(SymbolNotTradingException.class, OpenApiErrorCode.SYMBOL_NOT_TRADING),
            Map.entry(MarketOrdersDisabledException.class, OpenApiErrorCode.MARKET_ORDERS_DISABLED),
            Map.entry(StopOrdersDisabledException.class, OpenApiErrorCode.STOP_ORDERS_DISABLED),
            Map.entry(TimeInForceNotSupportedException.class, OpenApiErrorCode.TIME_IN_FORCE_NOT_SUPPORTED),
            Map.entry(InvalidPriceException.class, OpenApiErrorCode.INVALID_PRICE),
            Map.entry(InvalidStopPriceException.class, OpenApiErrorCode.INVALID_STOP_PRICE),
            Map.entry(InvalidQuantityException.class, OpenApiErrorCode.INVALID_QUANTITY),
            Map.entry(QuoteOrderQtyRequiredException.class, OpenApiErrorCode.QUOTE_ORDER_QTY_REQUIRED),
            Map.entry(QuoteOrderQtyNotSupportedException.class, OpenApiErrorCode.QUOTE_ORDER_QTY_NOT_SUPPORTED),
            Map.entry(InvalidTrailingDeltaException.class, OpenApiErrorCode.INVALID_TRAILING_DELTA),
            Map.entry(NotionalOutOfRangeException.class, OpenApiErrorCode.NOTIONAL_OUT_OF_RANGE),
            Map.entry(OrderLimitExceededException.class, OpenApiErrorCode.ORDER_LIMIT_EXCEEDED),
            Map.entry(AlgoOrderLimitExceededException.class, OpenApiErrorCode.ALGO_ORDER_LIMIT_EXCEEDED),
            Map.entry(OrderNotFoundException.class, OpenApiErrorCode.ORDER_NOT_FOUND),
            Map.entry(OrderNotCancelableException.class, OpenApiErrorCode.ORDER_NOT_CANCELABLE),
            Map.entry(FokWithExpireBothNotSupportedException.class, OpenApiErrorCode.FOK_EXPIRE_BOTH_NOT_SUPPORTED),
            Map.entry(InvalidSideException.class, OpenApiErrorCode.INVALID_SIDE),
            Map.entry(InvalidOrderTypeException.class, OpenApiErrorCode.INVALID_ORDER_TYPE),
            Map.entry(InvalidTimeInForceException.class, OpenApiErrorCode.INVALID_TIME_IN_FORCE),
            Map.entry(InvalidSelfTradePreventionModeException.class, OpenApiErrorCode.INVALID_SELF_TRADE_PREVENTION_MODE),
            Map.entry(OrderNotAmendableException.class, OpenApiErrorCode.ORDER_NOT_AMENDABLE),
            Map.entry(AmendPendingException.class, OpenApiErrorCode.AMEND_PENDING),
            Map.entry(NothingToAmendException.class, OpenApiErrorCode.NOTHING_TO_AMEND),
            Map.entry(FieldNotAmendableException.class, OpenApiErrorCode.FIELD_NOT_AMENDABLE),
            Map.entry(ManualOrderNotAllowedException.class, OpenApiErrorCode.COPY_ACCOUNT_NOT_TRADABLE),
            Map.entry(MarketNotAllowedException.class, OpenApiErrorCode.MARKET_NOT_ALLOWED),
            Map.entry(MarketUnavailableException.class, OpenApiErrorCode.MARKET_UNAVAILABLE),
            Map.entry(EngineUnavailableException.class, OpenApiErrorCode.MARKET_PAUSED),
            Map.entry(InvalidTimeRangeException.class, OpenApiErrorCode.INVALID_TIME_RANGE),
            Map.entry(InsufficientBalanceException.class, OpenApiErrorCode.INSUFFICIENT_BALANCE));

    @ExceptionHandler({SymbolNotFoundException.class, SymbolNotTradingException.class,
            MarketOrdersDisabledException.class, StopOrdersDisabledException.class,
            TimeInForceNotSupportedException.class, InvalidPriceException.class, InvalidStopPriceException.class,
            InvalidQuantityException.class, QuoteOrderQtyRequiredException.class,
            QuoteOrderQtyNotSupportedException.class, InvalidTrailingDeltaException.class,
            NotionalOutOfRangeException.class, OrderLimitExceededException.class,
            AlgoOrderLimitExceededException.class, OrderNotFoundException.class, OrderNotCancelableException.class,
            FokWithExpireBothNotSupportedException.class, InvalidSideException.class,
            InvalidOrderTypeException.class, InvalidTimeInForceException.class,
            InvalidSelfTradePreventionModeException.class, OrderNotAmendableException.class,
            AmendPendingException.class, NothingToAmendException.class, FieldNotAmendableException.class,
            ManualOrderNotAllowedException.class, MarketNotAllowedException.class,
            MarketUnavailableException.class, EngineUnavailableException.class, InvalidTimeRangeException.class,
            InsufficientBalanceException.class})
    public ResponseEntity<HttpApiResponse<Void>> handle(RuntimeException e) {
        OpenApiErrorCode code = CODES.get(e.getClass());
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, e.getMessage()));
    }
}
