package com.exchange.admin.bff.common.errors;

import com.exchange.core.api.error.HttpErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** admin-bff's own codes, in the ranges of docs/reference/error-codes.md. Never renumbered. */
@Getter
public enum AdminErrorCode implements HttpErrorCode {
    INVALID_CREDENTIALS(2001, HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    TOO_MANY_ATTEMPTS(2002, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later."),
    NOT_AUTHENTICATED(2004, HttpStatus.UNAUTHORIZED, "No valid session -- log in and try again"),
    INVALID_TOTP_CODE(2009, HttpStatus.UNAUTHORIZED, "Invalid or expired code"),
    TOTP_ALREADY_ENROLLED(2010, HttpStatus.CONFLICT, "An authenticator is already enrolled"),
    TOTP_NOT_ENROLLED(2011, HttpStatus.BAD_REQUEST, "No authenticator is enrolled yet"),
    /** 403: authenticated fine, just not allowed. A 401 would send the admin in a circle. */
    ROLE_REQUIRED(2020, HttpStatus.FORBIDDEN, "Your role does not allow this"),
    STEP_UP_REQUIRED(2021, HttpStatus.FORBIDDEN, "This action needs a fresh authenticator code"),
    ADMIN_NOT_FOUND(2022, HttpStatus.NOT_FOUND, "No such admin"),
    EMAIL_ALREADY_REGISTERED(2023, HttpStatus.CONFLICT, "An admin with that email exists"),
    USER_NOT_FOUND(2024, HttpStatus.NOT_FOUND, "No such customer"),
    /** 409, not 400: the state was fine to ask for, it is simply already so. */
    ALREADY_IN_THAT_STATE(2025, HttpStatus.CONFLICT, "The account is already in that state"),
    API_KEY_NOT_FOUND(2026, HttpStatus.NOT_FOUND, "No such API key"),
    INVALID_KYC_LEVEL(2027, HttpStatus.BAD_REQUEST, "Approve to a level; NONE is what a rejection sets"),
    /** Locking yourself out is not a decision a screen should let anyone make by accident. */
    CANNOT_ACT_ON_SELF(2028, HttpStatus.CONFLICT, "You cannot do that to your own account"),
    WEAK_PASSWORD(2029, HttpStatus.BAD_REQUEST, "The password must be at least 12 characters"),
    /** A sub-account pays its master's fee and has none of its own. */
    FEE_OVERRIDE_ON_SUB_ACCOUNT(2030, HttpStatus.CONFLICT, "A sub-account pays its master's fee override: set it on the master"),
    INVALID_OVERRIDE(2031, HttpStatus.BAD_REQUEST, "The override is not valid"),
    OVERRIDE_NOT_FOUND(2032, HttpStatus.NOT_FOUND, "There is no such override to remove"),
    INVALID_RECIPIENT(2033, HttpStatus.BAD_REQUEST, "Payouts go to a customer's main account"),
    /** The spot range, numbered as web-bff numbers the same faults. */
    ORDER_NOT_FOUND(5015, HttpStatus.NOT_FOUND, "No such order"),
    INVALID_ORDER_STATE(5030, HttpStatus.BAD_REQUEST, "State is open, closed, or an order status"),
    /** Markets: 5001 as web-bff numbers an unknown symbol. */
    MARKET_NOT_FOUND(5001, HttpStatus.NOT_FOUND, "No such market"),
    INVALID_MARKET(5031, HttpStatus.BAD_REQUEST, "The market's settings are not valid"),
    MARKET_ALREADY_LISTED(5032, HttpStatus.CONFLICT, "That pair is already listed"),
    INVALID_FEE_LEVEL(5033, HttpStatus.BAD_REQUEST, "The fee level is not valid"),
    FEE_LEVEL_NOT_FOUND(5034, HttpStatus.NOT_FOUND, "No such fee level"),
    /** The wallet range, as in error-codes.md; the number matches web-bff's for the same fault. */
    INSUFFICIENT_BALANCE(4001, HttpStatus.CONFLICT, "The account holds less than that"),
    ASSET_NOT_FOUND(4002, HttpStatus.NOT_FOUND, "No such asset"),
    INVALID_AMOUNT(4003, HttpStatus.BAD_REQUEST, "The amount must be positive, to at most 8 decimal places"),

    COPY_LEADER_NOT_FOUND(8001, HttpStatus.NOT_FOUND, "No such leader"),
    COPY_LEADER_WRONG_STATE(8014, HttpStatus.CONFLICT, "That cannot be done to a leader in this state"),
    INVALID_COPY_SETTINGS(8015, HttpStatus.BAD_REQUEST, "The copy settings are not valid");

    private final int code;
    private final HttpStatus status;
    private final String message;

    AdminErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
}
