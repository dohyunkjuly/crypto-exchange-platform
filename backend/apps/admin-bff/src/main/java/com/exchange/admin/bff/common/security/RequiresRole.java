package com.exchange.admin.bff.common.security;

import com.exchange.admin.identity.AdminRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The least role that may call this. Unannotated means VIEWER: any admin may read. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    AdminRole value();
}
