package com.exchange.bff.common.errors;

import com.exchange.core.api.error.HttpErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Every code web-bff's own services/controllers can attach to an {@code ApiException}.
 * This app's own contract, not shared with open-api -- see {@code ErrorCode}'s javadoc for
 * why.
 *
 * <p>{@code AuthService} lives in this app (its only caller -- open-api has no login flow)
 * and throws these directly. {@code ApiKeyService} lives in {@code libs/domain/user}
 * instead (open-api needs it too) and throws its own plain, code-less exceptions;
 * {@code ApiKeyExceptionAdvice} is what maps those to the codes below.
 *
 * <p>2000s (auth), 3000s (rate limiting), 4000s (wallet), 5000s (orders) and 6000s
 * (market data), per the ranges reserved in {@code ErrorCode}'s own javadoc.
 *
 * <p>{@code Http} in the name is load-bearing: every entry here carries an
 * {@code HttpStatus}, which is exactly what a WebSocket frame has no room for. The
 * socket's codes live in {@link BffWsErrorCode} beside this, implementing the
 * transport-agnostic {@code ErrorCode} instead. Keep the two enums' numbers disjoint --
 * they are one app's contract split by transport, not two independent numbering spaces.
 */
@Getter
public enum BffHttpErrorCode implements HttpErrorCode {

    INVALID_CREDENTIALS(2001, HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    TOO_MANY_ATTEMPTS(2002, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later."),
    EMAIL_ALREADY_REGISTERED(2003, HttpStatus.CONFLICT, "Email is already registered"),
    NOT_AUTHENTICATED(2004, HttpStatus.UNAUTHORIZED,
            "No valid session -- log in and try again"),
    WITHDRAWAL_REQUIRES_ALLOWLIST(2005, HttpStatus.BAD_REQUEST,
            "A withdrawal-capable key needs an IP allowlist"),
    API_KEY_NOT_FOUND(2006, HttpStatus.NOT_FOUND, "No such API key"),
    // 403, not 401: the caller is authenticated perfectly well, the account simply is not
    // permitted to do this yet. A 401 would tell a client to log in again, which fixes
    // nothing and sends the user in a circle.
    KYC_REQUIRED(2007, HttpStatus.FORBIDDEN,
            "Identity verification is required for this action"),
    MFA_REQUIRED(2008, HttpStatus.FORBIDDEN,
            "Two-factor authentication must be enabled for this action"),
    INVALID_MFA_CODE(2009, HttpStatus.UNAUTHORIZED, "Invalid or expired verification code"),
    MFA_ALREADY_ENABLED(2010, HttpStatus.CONFLICT,
            "Two-factor authentication is already enabled"),
    MFA_NOT_ENABLED(2011, HttpStatus.BAD_REQUEST,
            "Two-factor authentication is not enabled on this account"),
    MFA_ENROLMENT_NOT_STARTED(2012, HttpStatus.BAD_REQUEST,
            "No two-factor enrolment is in progress"),
    // Distinct from INVALID_MFA_CODE: the code may well be right, but the half-finished
    // login it belonged to is gone. The client must start again from the password rather
    // than prompt for another code.
    MFA_CHALLENGE_EXPIRED(2013, HttpStatus.UNAUTHORIZED,
            "This login attempt has expired -- sign in again"),
    INVALID_VERIFICATION_CODE(2014, HttpStatus.BAD_REQUEST,
            "That code is incorrect or has expired"),
    EMAIL_NOT_VERIFIED(2015, HttpStatus.FORBIDDEN,
            "Confirm your email address before continuing"),
    KYC_ALREADY_VERIFIED(2016, HttpStatus.CONFLICT, "This account is already verified"),
    CODE_REQUESTED_TOO_SOON(2021, HttpStatus.TOO_MANY_REQUESTS,
            "A code was just sent -- wait a moment before asking for another"),
    INVALID_RESET_CODE(2020, HttpStatus.BAD_REQUEST,
            "That reset code is incorrect or has expired"),
    INVALID_GOOGLE_TOKEN(2017, HttpStatus.UNAUTHORIZED, "Google sign-in failed"),
    GOOGLE_SIGN_IN_UNAVAILABLE(2018, HttpStatus.SERVICE_UNAVAILABLE,
            "Google sign-in is not configured"),
    // 409 and a specific instruction, because the user CAN resolve this: sign in with the
    // password, then link Google from settings. A generic failure would leave them
    // retrying the button that cannot work.
    ACCOUNT_EXISTS_USE_PASSWORD(2019, HttpStatus.CONFLICT,
            "An account with this email already exists -- sign in with your password, "
                    + "then link Google from your profile"),

    GOOGLE_NOT_LINKED(2022, HttpStatus.NOT_FOUND,
            "No Google account is linked to this account"),
    // 409 rather than 403: the request is well-formed and the caller is allowed to do
    // this -- they just cannot do it yet, and there is one specific step that fixes it.
    // Unlinking the only way in would lock the account, so a password has to exist first.
    PASSWORD_REQUIRED_BEFORE_UNLINK(2023, HttpStatus.CONFLICT,
            "Set a password before unlinking Google, or it becomes the only way to sign in"),
    INVALID_CURRENT_PASSWORD(2024, HttpStatus.BAD_REQUEST, "That is not the current password"),
    ACCOUNT_FROZEN(2026, HttpStatus.FORBIDDEN, "This account is frozen"),
    NOT_A_MASTER_ACCOUNT(2027, HttpStatus.FORBIDDEN, "A sub-account cannot do this; use the master account"),
    SUB_ACCOUNT_LIMIT_REACHED(2028, HttpStatus.CONFLICT, "No more sub-accounts can be created"),
    SUB_ACCOUNT_NOT_FOUND(2029, HttpStatus.NOT_FOUND, "No such sub-account"),
    SUB_ACCOUNT_FROZEN(2030, HttpStatus.FORBIDDEN, "That sub-account is frozen"),
    /** Deliberately says no more: the customer is never told the account was suspended, or why. */
    TRADING_NOT_AVAILABLE(2031, HttpStatus.FORBIDDEN, "Trading is not available on this account"),
    NO_PASSWORD_SET(2025, HttpStatus.CONFLICT,
            "This account has no password yet -- turn on 2FA, or set one through Forgot password"),
    RATE_LIMIT_EXCEEDED(3001, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later."),
    INSUFFICIENT_BALANCE(4001, HttpStatus.BAD_REQUEST, "Insufficient balance"),
    ASSET_NOT_FOUND(4002, HttpStatus.NOT_FOUND, "No such asset"),
    SYMBOL_NOT_FOUND(5001, HttpStatus.NOT_FOUND, "No such symbol"),
    SYMBOL_NOT_TRADING(5002, HttpStatus.BAD_REQUEST, "Symbol is not open for trading"),
    MARKET_ORDERS_DISABLED(5003, HttpStatus.BAD_REQUEST,
            "Market orders are disabled for this symbol"),
    STOP_ORDERS_DISABLED(5004, HttpStatus.BAD_REQUEST,
            "Stop/take-profit orders are disabled for this symbol"),
    TIME_IN_FORCE_NOT_SUPPORTED(5005, HttpStatus.BAD_REQUEST,
            "This time in force is not supported for this order"),
    INVALID_PRICE(5006, HttpStatus.BAD_REQUEST, "Price is required and must be on the tick size"),
    INVALID_STOP_PRICE(5007, HttpStatus.BAD_REQUEST,
            "Stop price is required (unless trailing) and must be on the tick size"),
    INVALID_QUANTITY(5008, HttpStatus.BAD_REQUEST,
            "Quantity is out of range or off the step size"),
    QUOTE_ORDER_QTY_REQUIRED(5009, HttpStatus.BAD_REQUEST,
            "A market-firing BUY requires quoteOrderQty"),
    QUOTE_ORDER_QTY_NOT_SUPPORTED(5010, HttpStatus.BAD_REQUEST,
            "quoteOrderQty is not valid for this order"),
    INVALID_TRAILING_DELTA(5011, HttpStatus.BAD_REQUEST,
            "trailingDeltaBps must be between 10 and 2000, and only on a conditional order"),
    NOTIONAL_OUT_OF_RANGE(5012, HttpStatus.BAD_REQUEST,
            "Order notional is out of the symbol's allowed range"),
    ORDER_LIMIT_EXCEEDED(5013, HttpStatus.BAD_REQUEST, "Too many open orders"),
    ALGO_ORDER_LIMIT_EXCEEDED(5014, HttpStatus.BAD_REQUEST,
            "Too many open conditional orders"),
    ORDER_NOT_FOUND(5015, HttpStatus.NOT_FOUND, "No such order"),
    ORDER_NOT_CANCELABLE(5016, HttpStatus.CONFLICT,
            "Order is no longer open and cannot be canceled"),
    INVALID_TIME_RANGE(5017, HttpStatus.BAD_REQUEST,
            "The time range must run forwards and span at most 90 days"),
    MARKET_UNAVAILABLE(5018, HttpStatus.SERVICE_UNAVAILABLE, "This market is not available right now"),
    // 5019 was SELF_TRADE_NOT_ALLOWED, removed with the NONE mode. Never reuse it.
    FOK_EXPIRE_BOTH_NOT_SUPPORTED(5020, HttpStatus.BAD_REQUEST,
            "EXPIRE_BOTH cannot be combined with FOK"),
    INVALID_SIDE(5021, HttpStatus.BAD_REQUEST, "side must be BUY or SELL"),
    INVALID_ORDER_TYPE(5022, HttpStatus.BAD_REQUEST, "Unknown order type"),
    INVALID_TIME_IN_FORCE(5023, HttpStatus.BAD_REQUEST, "Unknown time in force"),
    INVALID_SELF_TRADE_PREVENTION_MODE(5024, HttpStatus.BAD_REQUEST,
            "selfTradePreventionMode must be EXPIRE_TAKER, EXPIRE_MAKER or EXPIRE_BOTH"),
    ORDER_NOT_AMENDABLE(5025, HttpStatus.CONFLICT, "This order can no longer be amended"),
    AMEND_PENDING(5026, HttpStatus.CONFLICT, "An amend of this order is still being processed"),
    NOTHING_TO_AMEND(5027, HttpStatus.BAD_REQUEST, "The amend changes nothing"),
    FIELD_NOT_AMENDABLE(5028, HttpStatus.BAD_REQUEST, "That field cannot be amended on this order"),
    COPY_ACCOUNT_NOT_TRADABLE(5029, HttpStatus.FORBIDDEN, "A copy account is traded by copying, not by hand"),
    MARKET_NOT_ALLOWED(5030, HttpStatus.BAD_REQUEST, "This account cannot trade this market"),
    // 503, not 400: nothing is wrong with the order, and the same one is welcome in a moment.
    MARKET_PAUSED(5031, HttpStatus.SERVICE_UNAVAILABLE, "Trading on this market is paused"),
    INVALID_CANDLE_INTERVAL(6001, HttpStatus.BAD_REQUEST,
            "Unknown candle interval -- use one of 1m, 5m, 15m, 1h, 4h, 1d"),
    NO_MARKET_DATA(6002, HttpStatus.NOT_FOUND, "No market data for this symbol yet"),
    INVALID_DEPTH_GROUPING(6003, HttpStatus.BAD_REQUEST,
            "Unreadable price grouping -- see the values on /bff/symbols"),
    LEADER_NOT_FOUND(8001, HttpStatus.NOT_FOUND, "No such leader"),
    LEADER_NOT_ACCEPTING(8002, HttpStatus.CONFLICT, "This leader is not taking followers right now"),
    ALREADY_FOLLOWING(8003, HttpStatus.CONFLICT, "You are already copying this leader"),
    COPY_LIMIT_REACHED(8004, HttpStatus.CONFLICT, "You have as many copy accounts as you can have"),
    COPY_AMOUNT_TOO_SMALL(8005, HttpStatus.BAD_REQUEST, "The amount is under what this mode needs"),
    CANNOT_FOLLOW_YOURSELF(8006, HttpStatus.BAD_REQUEST, "You cannot copy your own lead account"),
    FOLLOW_NOT_FOUND(8007, HttpStatus.NOT_FOUND, "No such copy account"),
    FOLLOW_NOT_ACTIVE(8008, HttpStatus.CONFLICT, "This copy account is closing or closed"),
    COPY_BALANCE_BELOW_MINIMUM(8009, HttpStatus.BAD_REQUEST,
            "That would leave less than a copy account must hold -- stop copying to take everything out"),
    ALREADY_LEADER(8010, HttpStatus.CONFLICT, "There is already an application for this account"),
    DISPLAY_NAME_TAKEN(8011, HttpStatus.CONFLICT, "That display name is taken"),
    NOT_A_LEADER(8012, HttpStatus.NOT_FOUND, "This account has no lead account"),
    LEAD_BALANCE_BELOW_MINIMUM(8013, HttpStatus.BAD_REQUEST,
            "A lead account keeps its minimum, and its coins, while it leads"),
    LEADER_NOT_ACTIVE(8014, HttpStatus.CONFLICT, "That cannot be done to this lead account now"),
    LEAD_ACCOUNT_NOT_FLAT(8016, HttpStatus.CONFLICT,
            "Sell what the lead account holds and cancel its open orders before stopping"),
    LEAD_AMOUNT_OUT_OF_RANGE(8017, HttpStatus.BAD_REQUEST, "A lead account starts with between the minimum and the maximum");

    private final int code;
    private final HttpStatus status;
    private final String message;

    BffHttpErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
}
