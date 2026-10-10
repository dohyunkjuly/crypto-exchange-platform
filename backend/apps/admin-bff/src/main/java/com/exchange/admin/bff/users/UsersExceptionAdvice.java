package com.exchange.admin.bff.users;

import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.core.api.response.HttpApiResponse;
import com.exchange.user.apikey.exception.ApiKeyNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The user domain's plain exceptions, mapped to this app's own codes (error-codes.md). */
@RestControllerAdvice
public class UsersExceptionAdvice {

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> apiKeyNotFound(ApiKeyNotFoundException e) {
        return ResponseEntity.status(AdminErrorCode.API_KEY_NOT_FOUND.getStatus())
                .body(HttpApiResponse.error(AdminErrorCode.API_KEY_NOT_FOUND, e.getMessage()));
    }
}
