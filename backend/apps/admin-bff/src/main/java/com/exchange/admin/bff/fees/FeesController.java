package com.exchange.admin.bff.fees;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.security.CurrentAdmin;
import com.exchange.admin.bff.common.security.RequiresRole;
import com.exchange.admin.bff.common.security.RequiresStepUp;
import com.exchange.admin.bff.fees.dto.FeeViews.Level;
import com.exchange.admin.bff.fees.dto.FeeViews.LevelBody;
import com.exchange.admin.identity.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The spot fee schedule, for staff. A change moves what the exchange earns, so it is an
 * OPERATOR's with a fresh authenticator code. The work is {@link FeesService}'s.
 */
@RestController
@RequestMapping("/admin/fees")
public class FeesController {

    private final FeesService service;

    public FeesController(FeesService service) {
        this.service = service;
    }

    @GetMapping
    public List<Level> list() {
        return service.list();
    }

    @PatchMapping("/{level}")
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Level change(@PathVariable int level, @Valid @RequestBody LevelBody body, @CurrentAdmin Authenticated who,
                        HttpServletRequest http) {
        return service.change(who.admin().getId(), level, body, http.getRemoteAddr());
    }

    @PostMapping
    @RequiresRole(AdminRole.OPERATOR)
    @RequiresStepUp
    public Level add(@Valid @RequestBody LevelBody body, @CurrentAdmin Authenticated who, HttpServletRequest http) {
        return service.add(who.admin().getId(), body, http.getRemoteAddr());
    }
}
