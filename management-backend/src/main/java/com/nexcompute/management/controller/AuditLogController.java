package com.nexcompute.management.controller;

import com.nexcompute.management.audit.AuditLogDto;
import com.nexcompute.management.audit.AuditQueryCriteria;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.domain.AuditLogArchive;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.AuditLogArchiveRepository;
import com.nexcompute.management.repository.AuditLogRepository;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 审计日志查询接口。
 * 三角色可见性（按 mentorIdAtOp 快照过滤）：
 *   管理员=全表；导师=自己 OR (STUDENT AND mentorIdAtOp=自己)；学生=自己。
 * 热表与归档表合并查询：时间范围跨 30 天边界时合并两表结果按 createdAt 统一排序。
 * 丰富筛选（时间/操作人/姓名/角色/类型/目标/结果/客户端/mentorIdAtOp/operationNo）+
 * 模糊（content/errorMessage/operatorName/targetId/clientInfo LIKE）+ operationNo 精确 + 多列排序。
 * 查询本身（GET）不记审计，避免自递归（D11）。
 */
@Slf4j
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final Duration HOT_RETENTION = Duration.ofDays(30);
    private static final int MERGE_MAX = 10000;

    private final AuditLogRepository auditLogRepository;
    private final AuditLogArchiveRepository auditLogArchiveRepository;

    @GetMapping
    public ApiResponse<Page<AuditLogDto>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String operatorRole,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String clientInfo,
            @RequestParam(required = false) Long mentorIdAtOp,
            @RequestParam(required = false) String operationNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UserPrincipal viewer = SecurityUtils.getCurrentUser();
        UserRole role = viewer.getRole();
        AuditQueryCriteria criteria = AuditQueryCriteria.builder()
                .viewerRole(role).viewerId(viewer.getId())
                .start(start).end(end)
                .operatorId(operatorId).operatorName(operatorName).operatorRole(operatorRole)
                .action(action).targetType(targetType).targetId(targetId).result(result)
                .clientInfo(clientInfo).mentorIdAtOp(mentorIdAtOp).operationNo(operationNo)
                .keyword(keyword)
                .build();

        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortField = sanitizeSort(sortBy);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(dir, sortField));

        Instant now = Instant.now();
        Instant boundary = now.minus(HOT_RETENTION);
        boolean crossesBoundary = (start != null && start.isBefore(boundary))
                || (end != null && end.isBefore(boundary))
                || (start == null && end == null);

        // operationNo 精确定位：优先精确查（两表都可能命中）
        if (operationNo != null && !operationNo.isBlank()) {
            return ApiResponse.success(findByOperationNo(operationNo, pageable));
        }

        if (!crossesBoundary) {
            // 纯热表
            Page<AuditLog> hotPage = auditLogRepository.findAll(AuditQueryCriteria.specOf(criteria), pageable);
            Page<AuditLogDto> mapped = hotPage.map(AuditLogDto::fromHot);
            return ApiResponse.success(mapped);
        }

        // 跨边界：两表同 WHERE 查（不分页），内存合并排序 + 分页
        List<AuditLog> hot = auditLogRepository.findAll(AuditQueryCriteria.specOf(criteria));
        List<AuditLogArchive> arch = auditLogArchiveRepository.findAll(AuditQueryCriteria.specOf(criteria));

        List<AuditLogDto> merged = new ArrayList<>(hot.size() + arch.size());
        hot.forEach(l -> merged.add(AuditLogDto.fromHot(l)));
        arch.forEach(l -> merged.add(AuditLogDto.fromArchive(l)));

        Comparator<AuditLogDto> cmp = comparator(sortField);
        if (dir == Sort.Direction.DESC) cmp = cmp.reversed();
        merged.sort(cmp);

        int total = merged.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AuditLogDto> content = (from >= to) ? List.of() : merged.subList(from, to);
        return ApiResponse.success(new PageImpl<>(content, pageable, Math.min(total, MERGE_MAX)));
    }

    private Page<AuditLogDto> findByOperationNo(String operationNo, PageRequest pageable) {
        Page<AuditLog> hot = auditLogRepository.findByOperationNo(operationNo, pageable);
        if (!hot.isEmpty()) return hot.map(AuditLogDto::fromHot);
        Page<AuditLogArchive> arch = auditLogArchiveRepository.findByOperationNo(operationNo, pageable);
        return arch.map(AuditLogDto::fromArchive);
    }

    private String sanitizeSort(String sortBy) {
        if (sortBy == null) return "createdAt";
        switch (sortBy) {
            case "createdAt": case "operatorId": case "action": case "result": case "targetType":
                return sortBy;
            case "operatorName": case "targetId": case "operationNo": case "mentorIdAtOp":
                return sortBy;
            default: return "createdAt";
        }
    }

    private Comparator<AuditLogDto> comparator(String field) {
        switch (field) {
            case "operatorId": return Comparator.comparing(AuditLogDto::getOperatorId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "action": return Comparator.comparing(AuditLogDto::getAction, Comparator.nullsLast(Comparator.naturalOrder()));
            case "result": return Comparator.comparing(AuditLogDto::getResult, Comparator.nullsLast(Comparator.naturalOrder()));
            case "targetType": return Comparator.comparing(AuditLogDto::getTargetType, Comparator.nullsLast(Comparator.naturalOrder()));
            case "operatorName": return Comparator.comparing(AuditLogDto::getOperatorName, Comparator.nullsLast(Comparator.naturalOrder()));
            default: return Comparator.comparing(AuditLogDto::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        }
    }
}
