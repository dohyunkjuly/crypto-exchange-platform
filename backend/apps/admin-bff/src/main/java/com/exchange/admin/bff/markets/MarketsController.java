package com.exchange.admin.bff.markets;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.markets.dto.MarketViews.CreateBody;
import com.exchange.admin.bff.markets.dto.MarketViews.LimitsBody;
import com.exchange.admin.bff.markets.dto.MarketViews.Listing;
import com.exchange.admin.bff.markets.dto.MarketViews.Row;
import com.exchange.admin.bff.markets.dto.MarketViews.StatusBody;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Markets, for staff. Each write changes what can trade and at what price, so it is an OPERATOR's
 * with a fresh authenticator code (docs/reference/admin.md). The work is {@link MarketsService}'s.
 */
@RestController
@RequestMapping("/admin/markets")
public class MarketsController {

    private final MarketsService service;

    public MarketsController(MarketsService service) {
        this.service = service;
    }

    @GetMapping
    public Listing list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Row get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row create(@Valid @RequestBody CreateBody body, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.create(who.admin().getId(), body, http.getRemoteAddr());
    }

    @PostMapping("/{id}/status")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row status(@PathVariable Long id, @Valid @RequestBody StatusBody body, @CurrentAdmin Authenticated who,
                      HttpServletRequest http) {
        return service.setStatus(who.admin().getId(), id, body.status(), http.getRemoteAddr());
    }

    @PatchMapping("/{id}/limits")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Row limits(@PathVariable Long id, @Valid @RequestBody LimitsBody body, @CurrentAdmin Authenticated who,
                      HttpServletRequest http) {
        return service.setLimits(who.admin().getId(), id, body, http.getRemoteAddr());
    }
}
