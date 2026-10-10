package com.exchange.admin.bff.auth;

import com.exchange.admin.bff.auth.dto.AdminView;
import com.exchange.admin.bff.auth.dto.CodeRequest;
import com.exchange.admin.bff.auth.dto.EnrolmentView;
import com.exchange.admin.bff.auth.dto.LoginRequest;
import com.exchange.admin.bff.auth.dto.LoginView;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The login in two halves. {@code login} sets the cookie on a PENDING session and says
 * whether to ask for a code or to enrol first; {@code totp/enrol} hands out the QR once;
 * {@code totp/verify} takes the code and makes the session ACTIVE. {@code me} is what the
 * admin app calls on every page to learn who is logged in and whether the login is complete.
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginView login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                           HttpServletResponse response) {
        AdminAuthService.Login login = auth.login(request.email(), request.password(), http.getRemoteAddr(),
                http.getHeader(HttpHeaders.USER_AGENT));
        response.addHeader(HttpHeaders.SET_COOKIE,
                AdminSessionCookie.set(login.token().raw(), login.session().getExpiresAt()).toString());
        return new LoginView(login.totpEnrolled() ? LoginView.CODE : LoginView.ENROL);
    }

    @PostMapping("/totp/enrol")
    public EnrolmentView enrol(@CurrentAdmin Authenticated who) {
        return new EnrolmentView(auth.startTotpEnrolment(who));
    }

    @PostMapping("/totp/verify")
    public AdminView verify(@Valid @RequestBody CodeRequest request, @CurrentAdmin Authenticated who,
                            HttpServletRequest http) {
        auth.completeLogin(who, request.code(), http.getRemoteAddr());
        return AdminView.of(who.admin());
    }

    /** 401 on a PENDING session, like everything outside this controller: the app treats
     *  "not logged in" and "half logged in" the same way, by sending the admin to the login. */
    @GetMapping("/me")
    public AdminView me(@CurrentAdmin Authenticated who) {
        return AdminView.of(who.admin());
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(name = AdminSessionCookie.NAME, required = false) String token,
                       HttpServletRequest http, HttpServletResponse response) {
        auth.logout(token, http.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, AdminSessionCookie.clear().toString());
    }
}
