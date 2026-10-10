package com.exchange.admin.bff.audit;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.dto.AuditViews.Page_;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log, read back (docs/reference/admin.md). Any admin may read it: a log that only
 * its subjects' superiors can see is a log nobody checks. The work is {@link AdminAuditService}'s.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AdminAuditService service;

    public AuditController(AdminAuditService service) {
        this.service = service;
    }

    @GetMapping
    public Page_ list(@RequestParam(required = false) Long adminId,
                      @RequestParam(required = false) AdminAction action,
                      @RequestParam(required = false) String targetType,
                      @RequestParam(required = false) String targetId,
                      @RequestParam(required = false) Instant from,
                      @RequestParam(required = false) Instant to,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "50") int size) {
        return service.search(adminId, action, targetType, targetId, from, to, page, size);
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return service.actions();
    }
}
