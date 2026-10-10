package com.exchange.bff.wallet;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.wallet.asset.exception.AssetNotFoundException;
import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code AssetService}/{@code BalanceService}'s plain domain exceptions to this
 * app's own {@link BffHttpErrorCode}. Both services live in {@code libs/domain/wallet}
 * because open-api needs them too, and can't depend on either app's error codes -- so
 * each app owns this translation for itself. An equivalent small advice in open-api
 * does the same with its own codes.
 */
@RestControllerAdvice
public class WalletExceptionAdvice {

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInsufficientBalance(
            InsufficientBalanceException e) {
        return respond(BffHttpErrorCode.INSUFFICIENT_BALANCE, e.getMessage());
    }

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleAssetNotFound(AssetNotFoundException e) {
        return respond(BffHttpErrorCode.ASSET_NOT_FOUND, e.getMessage());
    }

    private ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code, message));
    }
}
