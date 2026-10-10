package com.exchange.bff.kyc;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.kyc.exception.KycAlreadyVerifiedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code KycService}'s failures to this app's codes, beside the feature that raises
 * them.
 *
 * <p>Distinct from {@code EligibilityExceptionAdvice}'s {@code KYC_REQUIRED}: that one
 * means "you have not verified far enough for this action" and comes back from trading and
 * from API keys. This one means "something went wrong submitting a verification", and can
 * only come from these endpoints.
 */
@RestControllerAdvice
public class KycExceptionAdvice {

    @ExceptionHandler(KycAlreadyVerifiedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleAlreadyVerified(
            KycAlreadyVerifiedException e) {
        return ResponseEntity.status(BffHttpErrorCode.KYC_ALREADY_VERIFIED.getStatus())
                .body(HttpApiResponse.error(BffHttpErrorCode.KYC_ALREADY_VERIFIED,
                        e.getMessage()));
    }
}
