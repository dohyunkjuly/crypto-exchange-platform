package com.exchange.admin.bff.treasury;

import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.wallet.asset.exception.AssetNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The wallet domain's plain exceptions, mapped to this app's own codes (error-codes.md). */
@RestControllerAdvice
public class TreasuryExceptionAdvice {

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> assetNotFound(AssetNotFoundException e) {
        return ResponseEntity.status(AdminErrorCode.ASSET_NOT_FOUND.getStatus())
                .body(HttpApiResponse.error(AdminErrorCode.ASSET_NOT_FOUND, e.getMessage()));
    }
}
