package com.exchange.admin.bff.copy;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.Page_;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.ProfitShare;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.Reason;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.Row;
import com.exchange.admin.identity.AdminRole;
import com.exchange.copy.leader.LeaderStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Copy trading's leaders, for staff: who leads, and stopping or restarting them. Each decision
 * changes who trades for others, so it is an OPERATOR's with a fresh code. The
 * work is {@link CopyLeadersService}'s.
 */
@RestController
@RequestMapping("/admin/copy/leaders")
public class CopyLeadersController {

    private final CopyLeadersService service;

    public CopyLeadersController(CopyLeadersService service) {
        this.service = service;
    }

    /** @param status one state, or everyone when absent */
    @GetMapping
    public Page_ list(@RequestParam(required = false) LeaderStatus status,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/{id}/suspend")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row suspend(@PathVariable UUID id, @Valid @RequestBody Reason request, @CurrentAdmin Authenticated who,
                       HttpServletRequest http) {
        return service.suspend(who.admin().getId(), id, request.reason().trim(), http.getRemoteAddr());
    }

    @PatchMapping("/{id}/profit-share")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row setProfitShare(@PathVariable UUID id, @Valid @RequestBody ProfitShare body,
                              @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.setProfitShare(who.admin().getId(), id, body.profitShareBps(), http.getRemoteAddr());
    }

    @PostMapping("/{id}/reinstate")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row reinstate(@PathVariable UUID id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.reinstate(who.admin().getId(), id, http.getRemoteAddr());
    }
}
