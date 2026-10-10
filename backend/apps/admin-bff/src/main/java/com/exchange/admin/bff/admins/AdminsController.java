package com.exchange.admin.bff.admins;

import com.exchange.admin.bff.admins.dto.AdminRequests.CreateRequest;
import com.exchange.admin.bff.admins.dto.AdminRequests.RoleRequest;
import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.auth.dto.AdminView;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Managing the staff themselves: SUPERADMIN only, every write audited, the role change
 *  behind a step-up since it is the one write here that cannot be taken back quietly. */
@RestController
@RequestMapping("/admin/admins")
public class AdminsController {

    private final AdminsService admins;

    public AdminsController(AdminsService admins) {
        this.admins = admins;
    }

    @GetMapping
    @RequiresRole(AdminRole.SUPERADMIN)
    public List<AdminView> list() {
        return admins.list().stream().map(AdminView::of).toList();
    }

    /** Minting a login is at least as sensitive as re-roling one, so it takes the same fresh code. */
    @PostMapping
    @RequiresRole(AdminRole.SUPERADMIN)
    @RequiresStepUp
    @ResponseStatus(HttpStatus.CREATED)
    public AdminView create(@Valid @RequestBody CreateRequest request, @CurrentAdmin Authenticated who,
                            HttpServletRequest http) {
        return AdminView.of(admins.create(who.admin().getId(), request.email().trim().toLowerCase(),
                request.password(), request.role(), http.getRemoteAddr()));
    }

    @PostMapping("/{id}/totp/reset")
    @RequiresRole(AdminRole.SUPERADMIN)
    @RequiresStepUp
    public AdminView resetTotp(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return AdminView.of(admins.resetTotp(who.admin().getId(), id, http.getRemoteAddr()));
    }

    @PatchMapping("/{id}/role")
    @RequiresRole(AdminRole.SUPERADMIN)
    @RequiresStepUp
    public AdminView changeRole(@PathVariable Long id, @Valid @RequestBody RoleRequest request,
                                @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return AdminView.of(admins.changeRole(who.admin().getId(), id, request.role(), http.getRemoteAddr()));
    }

    @PatchMapping("/{id}/disable")
    @RequiresRole(AdminRole.SUPERADMIN)
    public AdminView disable(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return AdminView.of(admins.disable(who.admin().getId(), id, http.getRemoteAddr()));
    }
}
