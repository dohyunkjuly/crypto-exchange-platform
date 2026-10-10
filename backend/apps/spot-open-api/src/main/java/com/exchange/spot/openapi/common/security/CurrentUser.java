package com.exchange.spot.openapi.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A controller parameter bound to the authenticated request: the {@code User} or the
 *  {@code UserApiKey}, whichever type the parameter declares. Resolved from what
 *  {@link ApiKeyAuthInterceptor} stashed; see {@link CurrentUserArgumentResolver}. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
