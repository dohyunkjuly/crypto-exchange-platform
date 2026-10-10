package com.exchange.spot.openapi.common.errors;

import com.exchange.core.api.error.ErrorCode;
import lombok.Getter;

/**
 * Every code open-api can send over its socket. Implements {@link ErrorCode} directly, not
 * {@code HttpErrorCode}: a frame has no status line. The 7000s are the socket's own protocol
 * failures; a condition that also exists over REST (the signed-request family at {@code auth})
 * carries the same number as {@link OpenApiErrorCode}, pinned by {@code ErrorCodeAgreementTest}.
 */
@Getter
public enum OpenApiWsErrorCode implements ErrorCode {
    MALFORMED_MESSAGE(7001, "Malformed message -- expected JSON"),
    SYMBOL_REQUIRED(7002, "A symbol is required"),
    UNKNOWN_ACTION(7003, "Unknown action -- use subscribe, unsubscribe or auth"),
    TOO_MANY_SUBSCRIPTIONS(7004, "Too many subscriptions on one connection"),
    UNKNOWN_CHANNEL(7005, "Unknown channel"),
    INVALID_INTERVAL(7006, "Unknown or missing interval"),
    INVALID_GROUPING(7007, "Unsupported price grouping for this market"),
    ALREADY_AUTHENTICATED(7008, "This connection is already authenticated"),
    MESSAGE_RATE_EXCEEDED(7009, "Too many messages on this connection"),
    TOO_MANY_CONNECTIONS(7010, "Too many connections"),
    INTERNAL_ERROR(9001, "Internal error");

    private final int code;
    private final String message;

    OpenApiWsErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
