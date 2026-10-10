package com.exchange.spot.openapi.common.security;

import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.user.eligibility.exception.AccountFrozenException;
import com.exchange.user.eligibility.exception.MfaRequiredException;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The account gate's plain exceptions, rendered with this app's codes -- the same mapping
 *  web-bff's {@code EligibilityExceptionAdvice} makes with its own numbers. 403 throughout: the
 *  request authenticated fine, the account may not do this today. */
@RestControllerAdvice
public class OpenApiEligibilityAdvice {

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<HttpApiResponse<Void>> emailNotVerified(EmailNotVerifiedException e) {
        return respond(OpenApiErrorCode.EMAIL_NOT_VERIFIED, e.getMessage());
    }

    @ExceptionHandler(KycRequiredException.class)
    public ResponseEntity<HttpApiResponse<Void>> kycRequired(KycRequiredException e) {
        return respond(OpenApiErrorCode.KYC_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<HttpApiResponse<Void>> mfaRequired(MfaRequiredException e) {
        return respond(OpenApiErrorCode.MFA_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ResponseEntity<HttpApiResponse<Void>> frozen(AccountFrozenException e) {
        return respond(OpenApiErrorCode.ACCOUNT_FROZEN, e.getMessage());
    }

    @ExceptionHandler(TradingSuspendedException.class)
    public ResponseEntity<HttpApiResponse<Void>> tradingSuspended(TradingSuspendedException e) {
        return respond(OpenApiErrorCode.TRADING_NOT_AVAILABLE, OpenApiErrorCode.TRADING_NOT_AVAILABLE.getMessage());
    }

    private static ResponseEntity<HttpApiResponse<Void>> respond(OpenApiErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
