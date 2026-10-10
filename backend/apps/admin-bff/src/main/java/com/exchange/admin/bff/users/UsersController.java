package com.exchange.admin.bff.users;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.users.dto.UserViews;
import com.exchange.admin.bff.users.dto.UserViews.Changed;
import com.exchange.admin.bff.users.dto.UserViews.Detail;
import com.exchange.admin.bff.users.dto.UserViews.ReasonRequest;
import com.exchange.admin.bff.users.dto.UserViews.Summary;
import com.exchange.admin.identity.AdminRole;
import com.exchange.user.identity.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers, for staff (docs/reference/admin.md). Reads are any admin's; each write is an
 * OPERATOR's and lands with its audit row in one transaction. None of it moves money, so none
 * of it asks for a step-up code. The work is {@link UsersService}'s.
 */
@RestController
@RequestMapping("/admin/users")
public class UsersController {

    private final UsersService service;

    public UsersController(UsersService service) {
        this.service = service;
    }

    /** Primary accounts, newest first; a sub is reached through its master's detail. */
    @GetMapping
    public UserViews.Page list(@RequestParam(required = false) String q,
                               @RequestParam(required = false) UserStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size) {
        return service.list(q, status, page, size);
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/freeze")
    @RequiresRole(AdminRole.OPERATOR)
    public Changed freeze(@PathVariable Long id, @Valid @RequestBody ReasonRequest request,
                          @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return changed(service.freeze(who.admin().getId(), id, request.reason().trim(), http.getRemoteAddr()));
    }

    /** Trading only: the customer still logs in, reads and cancels. Orders on the book stay. */
    @PostMapping("/{id}/suspend-trading")
    @RequiresRole(AdminRole.OPERATOR)
    public Summary suspendTrading(@PathVariable Long id, @Valid @RequestBody ReasonRequest request,
                                  @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return Summary.of(service.suspendTrading(who.admin().getId(), id, request.reason().trim(), http.getRemoteAddr()));
    }

    @PostMapping("/{id}/resume-trading")
    @RequiresRole(AdminRole.OPERATOR)
    public Summary resumeTrading(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return Summary.of(service.resumeTrading(who.admin().getId(), id, http.getRemoteAddr()));
    }

    @PostMapping("/{id}/unfreeze")
    @RequiresRole(AdminRole.OPERATOR)
    public Changed unfreeze(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return changed(service.unfreeze(who.admin().getId(), id, http.getRemoteAddr()));
    }

    @PostMapping("/{id}/sessions/revoke")
    @RequiresRole(AdminRole.OPERATOR)
    public Map<String, Integer> revokeSessions(@PathVariable Long id, @CurrentAdmin Authenticated who,
                                               HttpServletRequest http) {
        return Map.of("sessionsRevoked", service.revokeSessions(who.admin().getId(), id, http.getRemoteAddr()));
    }

    /** {@code ownerId} is the customer or one of their subs -- whichever row the key belongs to. */
    @DeleteMapping("/{id}/api-keys/{keyId}")
    @RequiresRole(AdminRole.OPERATOR)
    public Map<String, Boolean> revokeApiKey(@PathVariable Long id, @PathVariable UUID keyId,
                                             @RequestParam(required = false) Long ownerId,
                                             @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.revokeApiKey(who.admin().getId(), id, ownerId, keyId, http.getRemoteAddr());
        return Map.of("revoked", true);
    }

    private static Changed changed(UsersService.Outcome out) {
        return new Changed(Summary.of(out.user()), out.subsAffected(), out.sessionsRevoked());
    }
}
