package com.nexcompute.management.controller;

import com.nexcompute.management.audit.AuditSwitchService;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计开关接口。
 * 开关切换恒审计（force=true 不受开关影响，D12）：即使审计关闭，切换操作仍被记录。
 */
@RestController
@RequestMapping("/admin/audit-switch")
@RequiredArgsConstructor
public class AuditSwitchController {

    private final AuditSwitchService auditSwitchService;

    @GetMapping
    @RequirePermission(module = "audit", action = RequirePermission.Action.VIEW)
    public ApiResponse<Map<String, Boolean>> status() {
        return ApiResponse.success(Map.of("enabled", auditSwitchService.isAuditEnabled()));
    }

    /** 切换审计开关；恒审计记录（force=true）防掩盖痕迹 */
    @PostMapping
    @RequirePermission(module = "audit", action = RequirePermission.Action.EDIT)
    @Audited(action = "AUDIT_SWITCH_TOGGLE", targetType = "SYSTEM", targetIdExpr = "#enabled", force = true)
    public ApiResponse<Map<String, Boolean>> toggle(@RequestParam boolean enabled) {
        boolean now = auditSwitchService.setAuditEnabled(enabled);
        return ApiResponse.success(Map.of("enabled", now));
    }
}
