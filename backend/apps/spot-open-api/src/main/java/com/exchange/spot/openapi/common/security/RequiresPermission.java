package com.exchange.spot.openapi.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission a signed endpoint needs from the key, checked by {@link ApiKeyAuthInterceptor}
 * after the signature. Placing and cancelling need {@link Permission#TRADE}, everything else
 * {@link Permission#READ}. Absent on a signed endpoint means READ.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    Permission value();
}
