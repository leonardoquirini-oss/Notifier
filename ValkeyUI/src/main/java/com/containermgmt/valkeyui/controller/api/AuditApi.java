package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.AuditEntry;
import com.containermgmt.valkeyui.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditApi {

    private final AuditService auditService;

    @GetMapping
    public List<AuditEntry> recent(@RequestParam(value = "limit", defaultValue = "200") int limit) {
        return auditService.recent(limit);
    }

}
