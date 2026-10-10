package com.exchange.user.apikey.exception;

/** No such key, or it belongs to a different user -- deliberately the same failure either
 *  way, so a caller can't use it to probe which key IDs exist. */
public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException() {
        super("No such API key");
    }
}
