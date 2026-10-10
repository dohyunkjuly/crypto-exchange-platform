package com.exchange.bff.common.security;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.user.eligibility.exception.AccountFrozenException;
import com.exchange.user.eligibility.exception.MfaRequiredException;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders "you are authenticated, but not permitted to do this" as an error code.
 *
 * <p>Beside the {@link TradingEligibilityInterceptor} that throws these, rather than in
 * {@code common/errors} -- which holds the error contract itself, and is a cleaner package
 * for being only that. It cannot live with a feature the way {@code OrderExceptionAdvice}
 * does, because the same {@link KycRequiredException} comes back from placing an order,
 * from creating an API key, and from withdrawals next; putting it in any one of those
 * would make the others' mapping look accidental.
 *
 * <p>403 rather than 401 throughout: the caller authenticated perfectly well. A 401 tells
 * a client to log in again, which fixes nothing and sends the user round in a circle.
 *
 * <p>{@link KycRequiredException} carries the level it needed. That is passed through in
 * the message so a client can send the user to the right step -- "upload an ID" and "upload
 * a proof of address" are different screens, and a generic "verify your account" is how
 * people give up.
 */
@RestControllerAdvice
public class EligibilityExceptionAdvice {

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleEmailNotVerified(
            EmailNotVerifiedException e) {
        return respond(BffHttpErrorCode.EMAIL_NOT_VERIFIED, e.getMessage());
    }

    @ExceptionHandler(KycRequiredException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleKycRequired(KycRequiredException e) {
        return respond(BffHttpErrorCode.KYC_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMfaRequired(MfaRequiredException e) {
        return respond(BffHttpErrorCode.MFA_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleFrozen(AccountFrozenException e) {
        return respond(BffHttpErrorCode.ACCOUNT_FROZEN, e.getMessage());
    }

    @ExceptionHandler(TradingSuspendedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleTradingSuspended(TradingSuspendedException e) {
        return respond(BffHttpErrorCode.TRADING_NOT_AVAILABLE, BffHttpErrorCode.TRADING_NOT_AVAILABLE.getMessage());
    }

    private ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
