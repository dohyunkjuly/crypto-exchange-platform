package com.exchange.core.api.exception;

import com.exchange.core.api.error.CommonErrorCode;
import com.exchange.core.api.error.ErrorCode;
import com.exchange.core.api.error.HttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every HTTP controller in every app renders errors through here, in one shape, instead of
 * each one hand-rolling its own. Picked up automatically -- both apps' {@code
 * scanBasePackages} already include {@code com.exchange.core}.
 *
 * <p>HTTP-specific on purpose: {@link ApiException} itself is transport-neutral (it can be
 * thrown from domain code with no idea whether HTTP or a future WebSocket handler will
 * catch it), but rendering it as a status line + JSON body is exactly what makes this class
 * HTTP's, not shared with whatever a WS error frame ends up looking like.
 */
@RestControllerAdvice
public class HttpApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpApiExceptionHandler.class);

    /** A caller did something the domain rejected -- an expected, named failure. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleApiException(ApiException e) {
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode instanceof HttpErrorCode httpErrorCode
                ? httpErrorCode.getStatus()
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(HttpApiResponse.error(errorCode, e.getMessage()));
    }

    /** A {@code @Valid} request body failed. First field error wins -- good enough for a
     *  human to fix their request; the full set is in the framework's own log line. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException e) {
        String message = Optional.ofNullable(e.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse(CommonErrorCode.VALIDATION_FAILED.getMessage());
        return validationFailed(message);
    }

    /** A body that is not JSON, or has a value of the wrong type. An app that wants a
     *  dedicated code for a field binds it as a String and parses it itself. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException e) {
        return validationFailed("Malformed request body");
    }

    /** A path or query parameter that does not convert, such as an unknown enum name. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return validationFailed("Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return validationFailed("Parameter '" + e.getParameterName() + "' is required");
    }

    /** The caller's mistake, not a bug: without these two, a wrong path or verb is a logged 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleNoSuchEndpoint(NoResourceFoundException e) {
        return ResponseEntity.status(CommonErrorCode.NOT_FOUND.getStatus())
                .body(HttpApiResponse.error(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(HttpApiResponse.error(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    /** Anything unclassified is a bug: logged loudly here, reported to the client as a
     *  bare 500 rather than leaking a stack trace or an exception message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HttpApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(HttpApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private static ResponseEntity<HttpApiResponse<Void>> validationFailed(String message) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(HttpApiResponse.error(CommonErrorCode.VALIDATION_FAILED, message));
    }
}
