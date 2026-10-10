package com.exchange.core.api.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * The codes {@code HttpApiExceptionHandler} itself needs, for failures that belong to no
 * single app -- a malformed request or a bug is the same kind of thing whether it happens
 * in web-bff or open-api. App-specific business codes go in that app's own
 * {@link ErrorCode} implementation instead.
 */
@Getter
public enum CommonErrorCode implements HttpErrorCode {

    VALIDATION_FAILED(1000, HttpStatus.BAD_REQUEST, "Validation failed"),
    NOT_FOUND(1001, HttpStatus.NOT_FOUND, "No such endpoint"),
    METHOD_NOT_ALLOWED(1002, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed on this endpoint"),

    INTERNAL_SERVER_ERROR(9000, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final int code;
    private final HttpStatus status;
    private final String message;

    CommonErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
}
