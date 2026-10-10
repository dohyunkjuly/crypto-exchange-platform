package com.exchange.spot.openapi.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a request costs against the per-minute weight budget. Absent means 1. A read that
 * scales with what it returns sets {@code perLimit}: the cost is then {@code max(value,
 * ceil(limit / perLimit))} over the request's {@code limit} parameter, so a 5,000-level book
 * costs fifty and a 20-level one costs one -- the usual shape for depth.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Weight {
    int value() default 1;
    int perLimit() default 0;
}
