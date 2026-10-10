package com.exchange.spot.openapi.subaccount;

import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.subaccount.exception.NotAMasterAccountException;
import com.exchange.user.subaccount.exception.SubAccountFrozenException;
import com.exchange.user.subaccount.exception.SubAccountLimitReachedException;
import com.exchange.user.subaccount.exception.SubAccountNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The domain's code-less exceptions, as this app's public codes. */
@RestControllerAdvice
public class SubAccountExceptionAdvice {

    @ExceptionHandler(NotAMasterAccountException.class)
    public ResponseEntity<HttpApiResponse<Void>> notAMaster(NotAMasterAccountException e) {
        return respond(OpenApiErrorCode.NOT_A_MASTER_ACCOUNT);
    }

    @ExceptionHandler(SubAccountLimitReachedException.class)
    public ResponseEntity<HttpApiResponse<Void>> limit(SubAccountLimitReachedException e) {
        return respond(OpenApiErrorCode.SUB_ACCOUNT_LIMIT_REACHED);
    }

    @ExceptionHandler(SubAccountNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> notFound(SubAccountNotFoundException e) {
        return respond(OpenApiErrorCode.SUB_ACCOUNT_NOT_FOUND);
    }

    @ExceptionHandler(SubAccountFrozenException.class)
    public ResponseEntity<HttpApiResponse<Void>> frozen(SubAccountFrozenException e) {
        return respond(OpenApiErrorCode.SUB_ACCOUNT_FROZEN);
    }

    private static ResponseEntity<HttpApiResponse<Void>> respond(OpenApiErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code));
    }
}
