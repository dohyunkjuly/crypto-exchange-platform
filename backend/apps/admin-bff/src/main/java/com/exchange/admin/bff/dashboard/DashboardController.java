package com.exchange.admin.bff.dashboard;

import com.exchange.admin.bff.dashboard.dto.DashboardViews.Dashboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The console's home: today so far against yesterday. The work is {@link DashboardService}'s. */
@RestController
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Dashboard get() {
        return service.get();
    }
}
