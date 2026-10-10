package com.exchange.bff.kyc;

import com.exchange.bff.common.security.CurrentUser;
import com.exchange.bff.kyc.dto.KycDetailsView;
import com.exchange.bff.kyc.dto.KycStatusView;
import com.exchange.bff.kyc.dto.SubmitKycRequest;
import com.exchange.user.identity.User;
import com.exchange.user.kyc.KycService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity verification, from the profile section.
 *
 * <p><b>Documents never come through here.</b> Passport photos, selfies and liveness
 * capture go from the browser straight to the verification provider's own SDK and servers,
 * which is where the forensics happen -- document tamper detection, face match against the
 * document photo, anti-spoofing against printed photos, video replay and deepfakes, plus
 * sanctions and PEP screening. None of that can be done in a browser: anything the client
 * decides can be forged, and an endpoint that accepted {@code "faceMatched": true} would
 * be asking an attacker to grade their own exam.
 *
 * <p>So this API handles only the parts the platform itself reasons about (see
 * {@link SubmitKycRequest}) and the decision that comes back. That is also why user_kyc_profiles stores a
 * provider applicant id and no document data at all: those images are the highest-liability
 * thing an exchange could hold, the provider holds them under its own regime, and a second
 * copy would only widen a breach.
 *
 * <p>Two endpoints are missing until a provider is chosen, and their absence is the whole
 * of what "no provider yet" means: one to mint an SDK session token for the frontend, and
 * one for the provider's webhook to deliver its decision into {@code KycService.approve}.
 * a reviewer on admin-bff does the second (docs/reference/admin.md).
 */
@RestController
@RequestMapping("/bff/kyc")
public class KycController {

    private final KycService kyc;

    public KycController(KycService kyc) {
        this.kyc = kyc;
    }

    @GetMapping
    public KycStatusView status(@CurrentUser User user) {
        return KycStatusView.of(kyc.statusOf(user.getId()));
    }

    /**
     * The submitted personal details, for the owner to see. {@code no-store}: the most
     * sensitive fields in the schema must not linger in a shared browser's HTTP cache.
     */
    @GetMapping("/details")
    public ResponseEntity<KycDetailsView> details(@CurrentUser User user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(KycDetailsView.of(kyc.statusOf(user.getId())));
    }

    /**
     * Submits the details and puts the profile in review. Grants nothing on its own.
     *
     * @throws com.exchange.user.kyc.exception.KycAlreadyVerifiedException the account is
     *         already verified -- resubmitting would drop it back to PENDING and revoke
     *         what it had unlocked
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KycStatusView submit(@Valid @RequestBody SubmitKycRequest request,
                                @CurrentUser User user, HttpServletRequest http) {
        kyc.submit(user.getId(), request.legalFirstName(), request.legalLastName(),
                request.dateOfBirth(), request.nationality(), request.residenceCountry(),
                clientIp(http));
        return KycStatusView.of(kyc.statusOf(user.getId()));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
