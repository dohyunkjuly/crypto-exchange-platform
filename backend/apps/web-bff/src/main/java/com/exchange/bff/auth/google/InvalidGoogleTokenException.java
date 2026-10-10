package com.exchange.bff.auth.google;

/**
 * The presented token is not a valid Google ID token for this application.
 *
 * <p>One exception for every reason -- bad signature, expired, wrong audience, malformed.
 * Distinguishing them tells whoever is probing which part of a forgery attempt to fix.
 */
public class InvalidGoogleTokenException extends RuntimeException {

    public InvalidGoogleTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidGoogleTokenException(String message) {
        super(message);
    }
}
