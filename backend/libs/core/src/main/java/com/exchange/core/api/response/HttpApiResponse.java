package com.exchange.core.api.response;

import com.exchange.core.api.error.ErrorCode;
import com.exchange.core.api.error.HttpErrorCode;

/**
 * The one shape every /bff and /v1 HTTP response takes, success or failure, so a client
 * never has to branch on "is this endpoint's shape different from that one's."
 *
 * <p>A controller never builds this directly -- it just returns its DTO, per
 * SPRING_BOOT_GUIDE.md. A successful return is auto-wrapped by
 * {@link HttpApiResponseBodyAdvice}; a thrown {@code ApiException} is wrapped by {@code
 * HttpApiExceptionHandler}. {@code status} is the plain numeric HTTP status (matching the
 * actual response line), not the enum name -- {@code code} is 0 on success, an
 * {@link ErrorCode}'s app-specific number on failure.
 */
public record HttpApiResponse<T>(int status, int code, T data, String message) {

    public static <T> HttpApiResponse<T> success(int status, T data) {
        return new HttpApiResponse<>(status, 0, data, "OK");
    }

    public static HttpApiResponse<Void> error(ErrorCode errorCode, String message) {
        int status = errorCode instanceof HttpErrorCode httpErrorCode
                ? httpErrorCode.getStatus().value()
                : 500;
        return new HttpApiResponse<>(status, errorCode.getCode(), null, message);
    }

    public static HttpApiResponse<Void> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }
}
