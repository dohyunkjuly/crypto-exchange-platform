package com.exchange.admin.bff.overrides;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.FeeBody;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.LimitBody;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.Overrides;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's fee and order-limit overrides, and what they come to. Reads are any admin's; a
 * write is an OPERATOR's, audited, and a fee -- which moves money -- also wants a fresh code. Every
 * write answers with the tab as it now stands. The work is {@link UserOverridesService}'s.
 */
@RestController
@RequestMapping("/admin/users/{id}/overrides")
public class UserOverridesController {

    private final UserOverridesService service;

    public UserOverridesController(UserOverridesService service) {
        this.service = service;
    }

    @GetMapping
    public Overrides get(@PathVariable Long id) {
        return service.view(id);
    }

    @PostMapping("/fee")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Overrides setFee(@PathVariable Long id, @Valid @RequestBody FeeBody body, @CurrentAdmin Authenticated who,
                            HttpServletRequest http) {
        service.setFee(who.admin().getId(), id, new UserOverridesService.Fee(body.makerFeeBps(), body.takerFeeBps(),
                body.reason(), body.expiresAt()), http.getRemoteAddr());
        return service.view(id);
    }

    @DeleteMapping("/fee")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Overrides removeFee(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.removeFee(who.admin().getId(), id, http.getRemoteAddr());
        return service.view(id);
    }

    @PostMapping("/order-limit")
    @RequiresRole(AdminRole.OPERATOR)
    public Overrides setOrderLimit(@PathVariable Long id, @Valid @RequestBody LimitBody body,
                                   @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.setOrderLimit(who.admin().getId(), id, new UserOverridesService.OrderLimit(body.maxOrders(),
                body.maxStops(), body.reason(), body.expiresAt()), http.getRemoteAddr());
        return service.view(id);
    }

    @DeleteMapping("/order-limit")
    @RequiresRole(AdminRole.OPERATOR)
    public Overrides removeOrderLimit(@PathVariable Long id, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.removeOrderLimit(who.admin().getId(), id, http.getRemoteAddr());
        return service.view(id);
    }

    @PostMapping("/markets")
    @RequiresRole(AdminRole.OPERATOR)
    public Overrides setMarketLimit(@PathVariable Long id, @Valid @RequestBody LimitBody body,
                                    @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.setMarketLimit(who.admin().getId(), id, new UserOverridesService.MarketLimit(body.symbol(),
                body.maxOrders(), body.maxStops(), body.reason(), body.expiresAt()), http.getRemoteAddr());
        return service.view(id);
    }

    @DeleteMapping("/markets/{symbol}")
    @RequiresRole(AdminRole.OPERATOR)
    public Overrides removeMarketLimit(@PathVariable Long id, @PathVariable String symbol,
                                       @CurrentAdmin Authenticated who, HttpServletRequest http) {
        service.removeMarketLimit(who.admin().getId(), id, symbol, http.getRemoteAddr());
        return service.view(id);
    }
}
