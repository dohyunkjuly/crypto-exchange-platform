package com.exchange.admin.bff.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Money or a price moves here: the request carries a fresh TOTP code in {@code X-Admin-TOTP},
 *  checked and spent before the controller runs. A session alone is never enough. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresStepUp {
}
