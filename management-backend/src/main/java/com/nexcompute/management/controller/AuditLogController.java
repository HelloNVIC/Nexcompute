package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.repository.AuditLogRepository;
import com.nexcompute.management.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 审计日志查询接口（任务 2.5）
 * 仅管理员可查询。
 */
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@RequirePermission(module = "audit", action = RequirePermission.Action.VIEW)
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ApiResponse<Page<AuditLog>> list(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLog> result;
        if (operatorId != null) {
            result = auditLogRepository.findByOperatorIdOrderByCreatedAtDesc(operatorId, pageable);
        } else if (start != null && end != null) {
            result = auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
        } else if (action != null && !action.isBlank()) {
            result = auditLogRepository.findByActionContainingOrderByCreatedAtDesc(action, pageable);
        } else {
            result = auditLogRepository.findAll(pageable);
        }
        return ApiResponse.success(result);
    }
}
