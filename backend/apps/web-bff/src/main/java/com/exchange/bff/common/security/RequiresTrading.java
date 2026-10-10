package com.exchange.bff.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that may only run for an account cleared to trade -- verified to
 * {@code BASIC} and holding an active second factor. Enforced by
 * {@link TradingEligibilityInterceptor}.
 *
 * <p><b>Opt-in, never a path pattern.</b> A pattern like {@code /bff/orders/**} would also
 * catch cancelling and reading, and someone whose verification has lapsed must still be
 * able to see their open orders and get out of their positions -- the same principle
 * behind a {@code CANCEL_ONLY} symbol. Requiring the annotation means the dangerous
 * mistake is impossible to make by forgetting something; the worst you can do is leave a
 * new endpoint ungated, which fails safe for the user and is visible in review.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTrading {
}
