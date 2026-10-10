package com.exchange.admin.bff.kyc;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.kyc.dto.KycViews.Approve;
import com.exchange.admin.bff.kyc.dto.KycViews.Queue;
import com.exchange.admin.bff.kyc.dto.KycViews.Reject;
import com.exchange.admin.bff.kyc.dto.KycViews.Submission;
import com.exchange.admin.identity.AdminRole;
import com.exchange.user.kyc.KycStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The compliance queue (docs/reference/admin.md). Approving is what opens trading, so it carries a
 * step-up code like a write that moves money: a stolen session approving a mule account is the
 * fraud KYC exists to stop. Rejecting closes trading, and takes the same code, so the two cannot
 * be told apart by cost. The work is {@link KycReviewService}'s.
 */
@RestController
@RequestMapping("/admin/kyc")
public class KycController {

    private final KycReviewService service;

    public KycController(KycReviewService service) {
        this.service = service;
    }

    @GetMapping
    public Queue list(@RequestParam(defaultValue = "PENDING") KycStatus status,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{userId}")
    public Submission get(@PathVariable Long userId) {
        return service.get(userId);
    }

    @PostMapping("/{userId}/approve")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Submission approve(@PathVariable Long userId, @Valid @RequestBody Approve request,
                              @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.approve(who.admin().getId(), userId, request.level(), request.expiresAt(), http.getRemoteAddr());
    }

    @PostMapping("/{userId}/reject")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Submission reject(@PathVariable Long userId, @Valid @RequestBody Reject request,
                             @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.reject(who.admin().getId(), userId, request.reason().trim(), http.getRemoteAddr());
    }
}
