package com.exchange.admin.bff.copy;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.copy.dto.CopySettingsViews.Settings;
import com.exchange.admin.bff.copy.dto.CopySettingsViews.SettingsBody;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What staff set for copy trading. Every write here moves money or a price -- the leader's
 * share, how far a copied buy may pay, what closes an account -- so each is an OPERATOR's
 * with a fresh code. The work is {@link CopySettingsService}'s.
 */
@RestController
@RequestMapping("/admin/copy/settings")
public class CopySettingsController {

    private final CopySettingsService service;

    public CopySettingsController(CopySettingsService service) {
        this.service = service;
    }

    @GetMapping
    public Settings get() {
        return service.get();
    }

    /** The whole row, as the form holds it. PATCH because that is the verb this app's edge lets through. */
    @PatchMapping
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Settings change(@Valid @RequestBody SettingsBody body, @CurrentAdmin Authenticated who,
                           HttpServletRequest http) {
        return service.change(who.admin().getId(), body, http.getRemoteAddr());
    }
}
