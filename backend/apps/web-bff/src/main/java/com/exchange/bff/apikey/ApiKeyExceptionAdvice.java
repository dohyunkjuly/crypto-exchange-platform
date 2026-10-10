package com.exchange.bff.apikey;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.apikey.exception.ApiKeyNotFoundException;
import com.exchange.user.apikey.exception.WithdrawalRequiresAllowlistException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code ApiKeyService}'s plain domain exceptions to this app's own {@link
 * BffHttpErrorCode}. That service lives in {@code libs/domain/user} because open-api needs it
 * too, and can't depend on either app's error codes -- so each app owns this translation
 * for itself. An equivalent small advice in open-api does the same with its own codes.
 */
@RestControllerAdvice
public class ApiKeyExceptionAdvice {

    @ExceptionHandler(WithdrawalRequiresAllowlistException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleWithdrawalRequiresAllowlist(
            WithdrawalRequiresAllowlistException e) {
        return respond(BffHttpErrorCode.WITHDRAWAL_REQUIRES_ALLOWLIST, e.getMessage());
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleApiKeyNotFound(ApiKeyNotFoundException e) {
        return respond(BffHttpErrorCode.API_KEY_NOT_FOUND, e.getMessage());
    }

    private ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
