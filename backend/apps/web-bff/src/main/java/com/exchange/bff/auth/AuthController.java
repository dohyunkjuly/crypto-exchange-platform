package com.exchange.bff.auth;

import com.exchange.bff.auth.dto.ChangePasswordRequest;
import com.exchange.bff.auth.dto.GoogleSignInRequest;
import com.exchange.bff.auth.dto.LinkedIdentityView;
import com.exchange.bff.auth.dto.LoginOutcome;
import com.exchange.bff.auth.dto.LoginRequest;
import com.exchange.bff.auth.dto.LoginView;
import com.exchange.bff.auth.dto.PasswordResetConfirmRequest;
import com.exchange.bff.auth.dto.PasswordResetRequest;
import com.exchange.bff.auth.dto.MfaLoginRequest;
import com.exchange.bff.auth.dto.LoginResult;
import com.exchange.bff.auth.dto.RegisterRequest;
import com.exchange.bff.auth.dto.VerifyEmailRequest;
import com.exchange.bff.auth.dto.UnlinkRequest;
import com.exchange.bff.auth.dto.UserView;
import com.exchange.bff.common.security.CurrentUser;
import com.exchange.user.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerification;
    private final GoogleAuthService googleAuth;
    private final PasswordResetService passwordReset;
    private final PasswordChangeService passwordChange;

    public AuthController(AuthService authService, EmailVerificationService emailVerification, GoogleAuthService googleAuth, PasswordResetService passwordReset, PasswordChangeService passwordChange) {
        this.authService = authService;
        this.emailVerification = emailVerification;
        this.googleAuth = googleAuth;
        this.passwordReset = passwordReset;
        this.passwordChange = passwordChange;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        User user = authService.register(request.email(), request.password(), request.name(),
                clientIp(http));
        return UserView.of(user);
    }

    /**
     * Password first. For an account with a second factor this returns a challenge and
     * <b>no cookie</b> -- see {@link #loginMfa}. Nothing is signed in until the code
     * arrives, so a stolen password alone cannot even read a balance.
     */
    @PostMapping("/login")
    public LoginView login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                           HttpServletResponse response) {
        LoginOutcome outcome = authService.login(request.email(), request.password(),
                clientIp(http), http.getHeader(HttpHeaders.USER_AGENT));
        if (outcome.isMfaRequired()) {
            return LoginView.challenge(outcome.challengeToken());
        }
        return LoginView.signedIn(setSession(outcome.session(), response));
    }

    /**
     * Signs in with Google, creating the account on first use.
     *
     * <p>Answers exactly like {@link #login}: a session, or a challenge when the account
     * has a second factor. Google proving who someone is does not prove they hold that
     * factor, and letting it stand in would make 2FA bypassable through their Google
     * account.
     */
    @PostMapping("/google")
    public LoginView google(@Valid @RequestBody GoogleSignInRequest request,
                            HttpServletRequest http, HttpServletResponse response) {
        LoginOutcome outcome = googleAuth.signIn(request.idToken(), clientIp(http),
                http.getHeader(HttpHeaders.USER_AGENT));
        if (outcome.isMfaRequired()) {
            return LoginView.challenge(outcome.challengeToken());
        }
        return LoginView.signedIn(setSession(outcome.session(), response));
    }

    /**
     * Which sign-in providers are attached, so a settings screen can offer "Link Google"
     * or "Unlink" rather than guessing.
     */
    @GetMapping("/identities")
    public List<LinkedIdentityView> identities(@CurrentUser User user) {
        return googleAuth.linkedIdentities(user.getId()).stream()
                .map(LinkedIdentityView::of)
                .toList();
    }

    /**
     * Detaches Google. Requires the account's password -- both that one exists, and that
     * the user can still produce it, since it is what they will be signing in with
     * afterwards.
     *
     * <p>POST rather than DELETE because it carries a body, the same reason as
     * {@code /bff/mfa/disable}: DELETE with a request body is dropped silently by some
     * clients and intermediaries, which here would look like a wrong password rather than
     * a lost body.
     */
    @PostMapping("/google/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkGoogle(@Valid @RequestBody UnlinkRequest request,
                             @CurrentUser User user, HttpServletRequest http) {
        googleAuth.unlinkGoogle(user, request.password(), clientIp(http));
    }

    /** The code that turns a challenge into a session. Accepts a TOTP code or a recovery
     *  code -- {@code MfaService} tells them apart, so someone without their phone does
     *  not need a different endpoint. */
    @PostMapping("/login/mfa")
    public UserView loginMfa(@Valid @RequestBody MfaLoginRequest request,
                             HttpServletRequest http, HttpServletResponse response) {
        LoginResult result = authService.completeMfaLogin(request.challengeToken(),
                request.code(), clientIp(http), http.getHeader(HttpHeaders.USER_AGENT));
        return setSession(result, response);
    }

    private UserView setSession(LoginResult result, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                SessionCookies.set(result.sessionToken(), result.expiresAt()).toString());
        return UserView.of(result.user());
    }

    /** Idempotent, so a missing cookie is not an error -- there's simply nothing to log
     *  out. Deliberately {@code @CookieValue}, not {@code @CurrentUser}: an already
     *  invalid/expired token must still succeed here, not 401. */
    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = SessionCookies.COOKIE_NAME, required = false)
                       String rawToken, HttpServletRequest http, HttpServletResponse response) {
        if (rawToken != null) {
            authService.logout(rawToken, clientIp(http));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clear().toString());
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@CurrentUser User user, HttpServletRequest http,
                          HttpServletResponse response) {
        authService.logoutAll(user.getId(), clientIp(http));
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clear().toString());
    }

    /**
     * Redeems the code.
     *
     * <p>Requires a session, which is what binds the two halves together: whoever succeeds
     * has proved they hold both the account and the inbox. Logging in does not require a
     * verified email -- only trading does -- so this is always reachable.
     */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                            @CurrentUser User user, HttpServletRequest http) {
        emailVerification.verify(user, request.code(), clientIp(http));
    }

    /**
     * Sends another code, invalidating the previous one.
     *
     * <p>Takes no address: it acts on the authenticated user. That is not only simpler --
     * it removes the enumeration problem entirely. An endpoint that accepted an email
     * would be a way to test which addresses have an account here, and "does this person
     * hold crypto" is exactly what a phisher wants to know before writing to them.
     */
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@CurrentUser User user, HttpServletRequest http) {
        emailVerification.send(user, clientIp(http));
    }

    /** Always 204, so this cannot be used to test which addresses have an account. */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                     HttpServletRequest http) {
        passwordReset.request(request.email(), clientIp(http));
    }

    /** Sets the new password and revokes every session -- if an attacker was already
     *  inside, the reset has to end that rather than just change the way back in. */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                     HttpServletRequest http) {
        passwordReset.confirm(request.email(), request.code(), request.newPassword(),
                request.mfaCode(), clientIp(http));
    }

    @GetMapping("/me")
    public UserView me(@CurrentUser User user) {
        return UserView.of(user);
    }

    /**
     * Changes the password from inside the account: the authenticator code when 2FA is on, the
     * current password when not, and no emailed code -- that is the logged-out reset. Keeps this
     * session and ends every other one.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, @CurrentUser User user,
                               @CookieValue(name = SessionCookies.COOKIE_NAME) String sessionToken,
                               HttpServletRequest http) {
        passwordChange.change(user, sessionToken, request.newPassword(), request.currentPassword(),
                request.mfaCode(), clientIp(http));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
