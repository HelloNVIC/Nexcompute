package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NasRegistrationApproveResponse;
import com.nexcompute.management.dto.NasRegistrationDetail;
import com.nexcompute.management.dto.NasRegistrationListItem;
import com.nexcompute.management.service.NasRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员 NAS 注册审批（nas-allocation D5）：列表/详情/批准/改角色/重申上游/拒绝/删除/批量刷新状态。
 * 仅管理员可操作（服务层 requireAdmin 校验 + @Audited 留痕）。
 * Web 后台角色组 id：FULL_ADMIN=40 / READONLY_ADMIN=41 / SHARING_ADMIN=42（见 {@link NasRegistrationService}）。
 */
@RestController
@RequestMapping("/admin/nas-registrations")
@RequiredArgsConstructor
public class NasRegistrationController {

    private final NasRegistrationService nasRegistrationService;

    /** 审批队列列表（可选 status 过滤） */
    @GetMapping
    public ApiResponse<List<NasRegistrationListItem>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(nasRegistrationService.list(status));
    }

    /** 详情（合并 TrueNAS 实时状态，404 翻 NOT_FOUND） */
    @GetMapping("/{id}")
    public ApiResponse<NasRegistrationDetail> getDetail(@PathVariable Long id) {
        return ApiResponse.success(nasRegistrationService.getDetail(id));
    }

    /** 批准并开通（选 Web 后台角色 40/41/42/无） */
    @PostMapping("/{id}/approve")
    public ApiResponse<NasRegistrationApproveResponse> approve(@PathVariable Long id,
                                                               @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.success(nasRegistrationService.approve(id, webuiGroupId(request)));
    }

    /** 改角色（已 APPROVED，更新附加组保留 builtin_users） */
    @PostMapping("/{id}/reapprove")
    public ApiResponse<NasRegistrationApproveResponse> reapprove(@PathVariable Long id,
                                                                @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.success(nasRegistrationService.reapprove(id, webuiGroupId(request)));
    }

    /** 重申上游（NOT_FOUND，用新密码重建用户） */
    @PostMapping("/{id}/reprovision")
    public ApiResponse<NasRegistrationApproveResponse> reprovision(@PathVariable Long id,
                                                                  @Valid @RequestBody ReprovisionRequest request) {
        return ApiResponse.success(nasRegistrationService.reprovision(id, request.getPassword(), request.getWebuiGroupId()));
    }

    /** 拒绝（填原因，名额不退，用户名释放） */
    @PostMapping("/{id}/reject")
    public ApiResponse<NasRegistrationListItem> reject(@PathVariable Long id,
                                                        @RequestBody(required = false) RejectRequest request) {
        String reason = (request == null) ? null : request.getRejectReason();
        return ApiResponse.success(nasRegistrationService.reject(id, reason));
    }

    /** 删除记录（不删 TrueNAS 用户） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        nasRegistrationService.delete(id);
        return ApiResponse.success();
    }

    /** 批量刷新已开通用户状态（404 翻 NOT_FOUND） */
    @PostMapping("/refresh-statuses")
    public ApiResponse<Map<String, Integer>> refreshStatuses() {
        return ApiResponse.success(nasRegistrationService.refreshAllStatuses());
    }

    private static Integer webuiGroupId(ActionRequest request) {
        return (request == null) ? null : request.getWebuiGroupId();
    }

    /** 批准/改角色请求：webuiGroupId 可空（=无角色） */
    @Data
    public static class ActionRequest {
        /** TrueNAS Web 后台角色组 id（40=完全/41=只读/42=共享管理员），null=无角色 */
        private Integer webuiGroupId;
    }

    @Data
    public static class ReprovisionRequest {
        @NotBlank
        @Size(min = 8, max = 128)
        private String password;

        private Integer webuiGroupId;
    }

    @Data
    public static class RejectRequest {
        @Size(max = 255)
        private String rejectReason;
    }
}
