package com.exchange.bff.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter to be resolved from the session cookie, the same way
 * {@code @CookieValue}/{@code @RequestBody} resolve theirs. Resolution runs before the
 * method body, so a missing/invalid/expired/revoked session throws {@code ApiException}
 * ({@code BffHttpErrorCode#NOT_AUTHENTICATED}) before the handler ever executes -- the same
 * "block before the method runs" job a guard does elsewhere, done as parameter binding
 * because every other controller here already leans on that rather than imperative checks
 * in the method body. See {@link CurrentUserArgumentResolver}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
