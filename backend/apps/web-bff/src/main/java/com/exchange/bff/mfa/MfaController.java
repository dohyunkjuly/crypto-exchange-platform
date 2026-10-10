package com.exchange.bff.mfa;

import com.exchange.bff.common.security.CurrentUser;
import com.exchange.bff.mfa.dto.MfaCodeRequest;
import com.exchange.bff.mfa.dto.MfaStatusView;
import com.exchange.bff.mfa.dto.RecoveryCodesView;
import com.exchange.bff.mfa.dto.TotpEnrolmentView;
import com.exchange.user.identity.User;
import com.exchange.user.mfa.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setting up and tearing down a second factor, from the profile section.
 *
 * <p>Enrolment is two calls on purpose. The first mints a secret and returns something to
 * scan; the second proves the authenticator actually works before anything is activated.
 * Enrolling in one step would let a mistyped or mis-scanned secret lock someone out of
 * their own account -- the failure would only surface at the next login, with no way back.
 *
 * <p>All of these sit behind the ordinary session gate and none behind
 * {@code @RequiresTrading}: setting up 2FA is precisely what an unverified account needs
 * to do in order to become able to trade.
 */
@RestController
@RequestMapping("/bff/mfa")
public class MfaController {

    private final MfaService mfa;

    /** Shown in the authenticator's list above every customer's code, so it is the operator's
     *  name and nothing in this repo supplies one: the product is white-label
     *  (docs/reference/white-label.md). No default, like admin-bff's -- a blank would ship. */
    private final String issuer;

    public MfaController(MfaService mfa,
                         @Value("${EXCHANGE_NAME}") String exchangeName) {
        this.mfa = mfa;
        this.issuer = exchangeName.trim();
    }

    @GetMapping
    public MfaStatusView status(@CurrentUser User user) {
        return new MfaStatusView(mfa.isEnabled(user.getId()),
                mfa.remainingRecoveryCodes(user.getId()), mfa.enabledAt(user.getId()).orElse(null));
    }

    /**
     * Step one: mint a secret to scan. Activates nothing.
     *
     * @throws com.exchange.user.mfa.exception.MfaAlreadyEnabledException a factor is
     *         already active -- disable it first, which itself requires passing it, so a
     *         stolen session cannot quietly swap the factor for its own
     */
    @PostMapping("/totp")
    public TotpEnrolmentView startEnrolment(@CurrentUser User user) {
        return TotpEnrolmentView.of(
                mfa.startTotpEnrolment(user.getId(), user.getEmail(), issuer));
    }

    /**
     * Step two: prove the authenticator works, and activate it.
     *
     * @return the recovery codes, in plaintext, for the only time they will ever exist
     */
    @PostMapping("/totp/confirm")
    public RecoveryCodesView confirmEnrolment(@Valid @RequestBody MfaCodeRequest request,
                                              @CurrentUser User user,
                                              HttpServletRequest http) {
        return new RecoveryCodesView(
                mfa.confirmTotpEnrolment(user.getId(), request.code(), clientIp(http)));
    }

    /**
     * A fresh set of recovery codes, retiring the old set. Requires passing the factor.
     *
     * <p>The client should offer this whenever {@code remainingRecoveryCodes} from {@link
     * #status} is running low, rather than waiting for zero: at zero, a user who has also
     * lost their authenticator has nothing left to pass this with.
     *
     * @return the new codes, in plaintext, for the only time they will ever exist
     */
    @PostMapping("/recovery-codes")
    public RecoveryCodesView regenerateRecoveryCodes(@Valid @RequestBody MfaCodeRequest request,
                                                    @CurrentUser User user,
                                                    HttpServletRequest http) {
        return new RecoveryCodesView(
                mfa.regenerateRecoveryCodes(user.getId(), request.code(), clientIp(http)));
    }

    /**
     * Turning it off requires passing it.
     *
     * <p>POST rather than DELETE because it carries a body: a code is required, and DELETE
     * with a request body is poorly supported by intermediaries and some HTTP clients drop
     * it silently -- which here would look like a wrong code rather than a lost body.
     */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@Valid @RequestBody MfaCodeRequest request, @CurrentUser User user,
                        HttpServletRequest http) {
        mfa.disable(user.getId(), request.code(), clientIp(http));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
