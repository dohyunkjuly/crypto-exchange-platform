package com.exchange.bff.mfa;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.mfa.exception.InvalidMfaCodeException;
import com.exchange.user.mfa.exception.MfaAlreadyEnabledException;
import com.exchange.user.mfa.exception.MfaEnrolmentNotStartedException;
import com.exchange.user.mfa.exception.MfaNotEnabledException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code MfaService}'s failures to this app's codes -- beside the feature that
 * surfaces them, the same way {@code OrderExceptionAdvice} sits beside orders.
 *
 * <p>Distinct from {@code EligibilityExceptionAdvice}, which handles a different question.
 * These four mean "something went wrong setting up or using your second factor". That one
 * means "your account is not permitted to do this yet" -- it comes back from placing an
 * order and from creating an API key, so it belongs with the authorization machinery
 * rather than here.
 *
 * <p>{@link InvalidMfaCodeException} is one code for wrong, expired and already-spent.
 * Splitting them would tell an attacker whether they hold the right secret and merely
 * mistimed it, which is exactly what a guessing attack needs to know.
 */
@RestControllerAdvice
public class MfaExceptionAdvice {

    @ExceptionHandler(InvalidMfaCodeException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidCode(InvalidMfaCodeException e) {
        return respond(BffHttpErrorCode.INVALID_MFA_CODE, e.getMessage());
    }

    @ExceptionHandler(MfaAlreadyEnabledException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleAlreadyEnabled(
            MfaAlreadyEnabledException e) {
        return respond(BffHttpErrorCode.MFA_ALREADY_ENABLED, e.getMessage());
    }

    @ExceptionHandler(MfaNotEnabledException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleNotEnabled(MfaNotEnabledException e) {
        return respond(BffHttpErrorCode.MFA_NOT_ENABLED, e.getMessage());
    }

    @ExceptionHandler(MfaEnrolmentNotStartedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleEnrolmentNotStarted(
            MfaEnrolmentNotStartedException e) {
        return respond(BffHttpErrorCode.MFA_ENROLMENT_NOT_STARTED, e.getMessage());
    }

    private ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
