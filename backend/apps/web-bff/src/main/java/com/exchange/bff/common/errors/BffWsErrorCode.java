package com.exchange.bff.common.errors;

import com.exchange.core.api.error.ErrorCode;
import lombok.Getter;

/**
 * Every code web-bff can send over a WebSocket.
 *
 * <p>Implements {@link ErrorCode} directly rather than {@code HttpErrorCode}, which is the
 * case {@code ErrorCode}'s own javadoc anticipates: a socket frame has no status line, and
 * every entry in {@link BffHttpErrorCode} must carry one. Putting these there would mean
 * inventing a status for each that nothing would ever read.
 *
 * <p>Separate enum, same folder, same app -- error codes stay each app's own contract per
 * AGENTS.md, and this is that contract's WebSocket half. The 7000s range belongs
 * to it; the numbers stay disjoint from {@link BffHttpErrorCode}'s so a code identifies
 * its meaning without the reader knowing which enum to open.
 *
 * <p>Everything here is a malformed request from a client. There is no domain failure in
 * the list because the socket reads no domain state -- it forwards what the engine already
 * published. If that changes, a domain {@code ApiException} thrown with a
 * {@link BffHttpErrorCode} still renders fine: the renderer only needs
 * {@code getCode()}/{@code getMessage()}, and ignores the status it cannot use.
 */
@Getter
public enum BffWsErrorCode implements ErrorCode {

    MALFORMED_MESSAGE(7001, "Malformed message -- expected JSON"),
    SYMBOL_REQUIRED(7002, "A symbol is required"),
    UNKNOWN_ACTION(7003, "Unknown action -- use subscribe or unsubscribe"),
    TOO_MANY_SUBSCRIPTIONS(7004, "Too many subscriptions on one connection"),
    UNKNOWN_CHANNEL(7005, "Unknown channel"),
    INVALID_INTERVAL(7006, "Unknown or missing interval"),
    INVALID_GROUPING(7007, "Unsupported price grouping for this market"),

    /** The socket's {@code CommonErrorCode.INTERNAL_SERVER_ERROR}: something escaped the
     *  handler that was never classified, so it is a bug. Reported without detail on
     *  purpose -- the stack trace belongs in the log, not on the wire. */
    INTERNAL_ERROR(9001, "Internal error");

    private final int code;
    private final String message;

    BffWsErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
