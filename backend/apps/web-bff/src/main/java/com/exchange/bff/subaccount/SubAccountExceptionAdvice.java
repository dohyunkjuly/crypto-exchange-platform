package com.exchange.bff.subaccount;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.subaccount.exception.NotAMasterAccountException;
import com.exchange.user.subaccount.exception.SubAccountFrozenException;
import com.exchange.user.subaccount.exception.SubAccountLimitReachedException;
import com.exchange.user.subaccount.exception.SubAccountNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The domain's code-less exceptions, as this app's codes. */
@RestControllerAdvice
public class SubAccountExceptionAdvice {

    @ExceptionHandler(NotAMasterAccountException.class)
    public ResponseEntity<HttpApiResponse<Void>> notAMaster(NotAMasterAccountException e) {
        return respond(BffHttpErrorCode.NOT_A_MASTER_ACCOUNT);
    }

    @ExceptionHandler(SubAccountLimitReachedException.class)
    public ResponseEntity<HttpApiResponse<Void>> limit(SubAccountLimitReachedException e) {
        return respond(BffHttpErrorCode.SUB_ACCOUNT_LIMIT_REACHED);
    }

    @ExceptionHandler(SubAccountNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> notFound(SubAccountNotFoundException e) {
        return respond(BffHttpErrorCode.SUB_ACCOUNT_NOT_FOUND);
    }

    @ExceptionHandler(SubAccountFrozenException.class)
    public ResponseEntity<HttpApiResponse<Void>> frozen(SubAccountFrozenException e) {
        return respond(BffHttpErrorCode.SUB_ACCOUNT_FROZEN);
    }

    private static ResponseEntity<HttpApiResponse<Void>> respond(BffHttpErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code));
    }
}
