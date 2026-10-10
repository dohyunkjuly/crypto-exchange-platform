package com.exchange.core.api.exception;

import com.exchange.core.api.error.CommonErrorCode;
import com.exchange.core.api.error.ErrorCode;
import lombok.Getter;

/**
 * Business/domain failure, transport-neutral -- thrown from code that has no idea whether
 * HTTP or (eventually) a WebSocket handler will end up rendering it. {@code
 * HttpApiExceptionHandler} is what knows how to turn one into an HTTP response today.
 * Anything else that escapes a controller is treated as a bug (mapped to
 * {@link CommonErrorCode#INTERNAL_SERVER_ERROR}) rather than a client-facing error, on
 * purpose -- an unclassified exception should be loud, not quietly wrapped in a
 * clean-looking 4xx.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** Same code, a message specific to this occurrence -- e.g. which field failed, not
     *  just that validation failed. */
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
