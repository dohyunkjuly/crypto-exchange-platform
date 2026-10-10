package com.exchange.bff.auth.google;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders a failed Google token verification.
 *
 * <p>The client is told only that sign-in failed. The reason -- bad signature, expired,
 * wrong audience -- is logged and not returned, because it is exactly the feedback someone
 * forging a token needs to work out which part to fix.
 */
@RestControllerAdvice
public class GoogleExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GoogleExceptionAdvice.class);

    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidToken(
            InvalidGoogleTokenException e) {
        log.warn("rejected a Google ID token", e);
        return ResponseEntity.status(BffHttpErrorCode.INVALID_GOOGLE_TOKEN.getStatus())
                .body(HttpApiResponse.error(BffHttpErrorCode.INVALID_GOOGLE_TOKEN));
    }
}
