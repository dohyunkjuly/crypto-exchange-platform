package com.exchange.copy.follow.exception;

/** Less than the mode asks for, or an amount per order the funding cannot cover. */
public class CopyAmountTooSmallException extends RuntimeException {

    public CopyAmountTooSmallException(String message) {
        super(message);
    }
}
