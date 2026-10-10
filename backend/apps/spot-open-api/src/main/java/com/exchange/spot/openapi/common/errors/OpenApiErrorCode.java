package com.exchange.spot.openapi.common.errors;

import com.exchange.core.api.error.HttpErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Every code open-api's own controllers and exception handlers can attach to an {@code
 * ApiException}. This app's own contract, not shared with web-bff -- see {@code ErrorCode}'s
 * javadoc for why -- and public: a number here is a promise to every bot written against it.
 *
 * <p>Ranges as {@code ErrorCode} lays them out. Where a meaning exists in web-bff too, the same
 * number is used by convention (an operator reading two logs should not have to translate), and
 * where it exists on the socket too, {@code OpenApiWsErrorCode} carries the same number, pinned
 * by a test. The 2100s are the signed-request family, which web-bff does not have.
 */
@Getter
public enum OpenApiErrorCode implements HttpErrorCode {

    UNKNOWN_API_KEY(2101, HttpStatus.UNAUTHORIZED, "Unknown API key"),
    INVALID_SIGNATURE(2102, HttpStatus.UNAUTHORIZED, "Bad signature"),
    TIMESTAMP_OUT_OF_WINDOW(2103, HttpStatus.UNAUTHORIZED,
            "Timestamp is outside the receive window"),
    IP_NOT_ALLOWED(2104, HttpStatus.FORBIDDEN, "This IP is not on the key's allowlist"),
    PERMISSION_DENIED(2105, HttpStatus.FORBIDDEN, "The key lacks the permission for this request"),
    API_KEY_INACTIVE(2106, HttpStatus.UNAUTHORIZED, "API key revoked or expired"),
    KYC_REQUIRED(2107, HttpStatus.FORBIDDEN, "Identity verification is required to trade"),
    MFA_REQUIRED(2108, HttpStatus.FORBIDDEN, "A second factor must be active on the account"),
    EMAIL_NOT_VERIFIED(2109, HttpStatus.FORBIDDEN, "The account's email is not verified"),
    ACCOUNT_FROZEN(2110, HttpStatus.FORBIDDEN, "The account is frozen"),
    NOT_A_MASTER_ACCOUNT(2111, HttpStatus.FORBIDDEN, "Only a master account's key may manage sub-accounts"),
    SUB_ACCOUNT_LIMIT_REACHED(2112, HttpStatus.CONFLICT, "No more sub-accounts can be created"),
    SUB_ACCOUNT_NOT_FOUND(2113, HttpStatus.NOT_FOUND, "No such sub-account"),
    SUB_ACCOUNT_FROZEN(2114, HttpStatus.FORBIDDEN, "That sub-account is frozen"),
    /** Deliberately says no more: the customer is never told the account was suspended, or why. */
    TRADING_NOT_AVAILABLE(2115, HttpStatus.FORBIDDEN, "Trading is not available on this account"),
    RATE_LIMIT_EXCEEDED(3001, HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    ASSET_NOT_FOUND(4002, HttpStatus.NOT_FOUND, "No such asset"),
    SYMBOL_NOT_FOUND(5001, HttpStatus.NOT_FOUND, "No such symbol"),
    SYMBOL_NOT_TRADING(5002, HttpStatus.BAD_REQUEST, "Symbol is not open for trading"),
    MARKET_ORDERS_DISABLED(5003, HttpStatus.BAD_REQUEST, "Market orders are disabled for this symbol"),
    STOP_ORDERS_DISABLED(5004, HttpStatus.BAD_REQUEST, "Stop/take-profit orders are disabled for this symbol"),
    TIME_IN_FORCE_NOT_SUPPORTED(5005, HttpStatus.BAD_REQUEST, "This time in force is not supported for this order"),
    INVALID_PRICE(5006, HttpStatus.BAD_REQUEST, "Price is required and must be on the tick size"),
    INVALID_STOP_PRICE(5007, HttpStatus.BAD_REQUEST, "Stop price is required (unless trailing) and must be on the tick size"),
    INVALID_QUANTITY(5008, HttpStatus.BAD_REQUEST, "Quantity is out of range or off the step size"),
    QUOTE_ORDER_QTY_REQUIRED(5009, HttpStatus.BAD_REQUEST, "A market-firing BUY requires quoteOrderQty"),
    QUOTE_ORDER_QTY_NOT_SUPPORTED(5010, HttpStatus.BAD_REQUEST, "quoteOrderQty is not valid for this order"),
    INVALID_TRAILING_DELTA(5011, HttpStatus.BAD_REQUEST, "trailingDeltaBps must be between 10 and 2000, and only on a conditional order"),
    NOTIONAL_OUT_OF_RANGE(5012, HttpStatus.BAD_REQUEST, "Order notional is out of the symbol's allowed range"),
    ORDER_LIMIT_EXCEEDED(5013, HttpStatus.BAD_REQUEST, "Too many open orders"),
    ALGO_ORDER_LIMIT_EXCEEDED(5014, HttpStatus.BAD_REQUEST, "Too many open conditional orders"),
    ORDER_NOT_FOUND(5015, HttpStatus.NOT_FOUND, "No such order"),
    ORDER_NOT_CANCELABLE(5016, HttpStatus.CONFLICT, "Order is no longer open and cannot be canceled"),
    INVALID_TIME_RANGE(5017, HttpStatus.BAD_REQUEST, "The time range must run forwards and span at most 90 days"),
    MARKET_UNAVAILABLE(5018, HttpStatus.SERVICE_UNAVAILABLE, "This market is not available right now"),
    // 5019 was SELF_TRADE_NOT_ALLOWED, removed with the NONE mode. Never reuse it.
    FOK_EXPIRE_BOTH_NOT_SUPPORTED(5020, HttpStatus.BAD_REQUEST, "EXPIRE_BOTH cannot be combined with FOK"),
    INVALID_SIDE(5021, HttpStatus.BAD_REQUEST, "side must be BUY or SELL"),
    INVALID_ORDER_TYPE(5022, HttpStatus.BAD_REQUEST, "Unknown order type"),
    INVALID_TIME_IN_FORCE(5023, HttpStatus.BAD_REQUEST, "Unknown time in force"),
    INVALID_SELF_TRADE_PREVENTION_MODE(5024, HttpStatus.BAD_REQUEST, "selfTradePreventionMode must be EXPIRE_TAKER, EXPIRE_MAKER or EXPIRE_BOTH"),
    ORDER_NOT_AMENDABLE(5025, HttpStatus.CONFLICT, "This order can no longer be amended"),
    AMEND_PENDING(5026, HttpStatus.CONFLICT, "An amend of this order is still being processed"),
    NOTHING_TO_AMEND(5027, HttpStatus.BAD_REQUEST, "The amend changes nothing"),
    FIELD_NOT_AMENDABLE(5028, HttpStatus.BAD_REQUEST, "That field cannot be amended on this order"),
    COPY_ACCOUNT_NOT_TRADABLE(5029, HttpStatus.FORBIDDEN, "A copy account is traded by copying, not by hand"),
    MARKET_NOT_ALLOWED(5030, HttpStatus.BAD_REQUEST, "This account cannot trade this market"),
    // 503, not 400: nothing is wrong with the order, and the same one is welcome in a moment.
    MARKET_PAUSED(5031, HttpStatus.SERVICE_UNAVAILABLE, "Trading on this market is paused"),
    INSUFFICIENT_BALANCE(4001, HttpStatus.BAD_REQUEST, "Insufficient balance"),
    INVALID_CANDLE_INTERVAL(6001, HttpStatus.BAD_REQUEST, "Unknown candle interval"),
    NO_MARKET_DATA(6002, HttpStatus.NOT_FOUND, "No market data for this symbol yet"),
    INVALID_DEPTH_GROUPING(6003, HttpStatus.BAD_REQUEST, "Unsupported price grouping");

    private final int code;
    private final HttpStatus status;
    private final String message;

    OpenApiErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
}
