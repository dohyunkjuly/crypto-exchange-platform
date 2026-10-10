package com.exchange.admin.bff.auth.dto;

/** What the browser does next: type a code, or scan an authenticator first. */
public record LoginView(String next) {

    public static final String CODE = "CODE";
    public static final String ENROL = "ENROL";
}
