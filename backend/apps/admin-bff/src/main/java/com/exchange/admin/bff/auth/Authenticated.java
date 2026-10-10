package com.exchange.admin.bff.auth;

import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.session.AdminSession;

/** What a valid cookie established. Stashed on the request by the interceptor. */
public record Authenticated(AdminUser admin, AdminSession session) {

    public static final String ATTRIBUTE = "authenticatedAdmin";
}
