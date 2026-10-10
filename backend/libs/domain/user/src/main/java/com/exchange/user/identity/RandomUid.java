package com.exchange.user.identity;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/** A customer id drawn at random rather than counted: see {@link RandomUidGenerator}. */
@IdGeneratorType(RandomUidGenerator.class)
@Retention(RUNTIME)
@Target(FIELD)
public @interface RandomUid {
}
