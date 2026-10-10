package com.exchange.spot.openapi.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The account gate on top of the key's permission: verified email, KYC at the trading level,
 * an active second factor -- {@code EligibilityService.requireCanTrade}. Placing an order
 * carries it; cancelling and reading never do (AGENTS.md: a lapsed account must still be able
 * to exit positions). The key stays cryptographically valid after KYC lapses, which is exactly
 * why this is checked per request and not at key creation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTrading {
}
