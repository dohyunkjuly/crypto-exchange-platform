package com.exchange.spot.openapi.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a request that spends the account's order budget (per 10 s and per day) as well as
 *  its weight. Placement only: a cancel must never be refused for having ordered too much. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CountsAsOrder {
}
