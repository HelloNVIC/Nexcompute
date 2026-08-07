package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NewApiRegistrationApproveResponse;
import com.nexcompute.management.dto.NewApiRegistrationDetail;
import com.nexcompute.management.dto.NewApiRegistrationListItem;
import com.nexcompute.management.service.NewApiRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员 NewAPI 注册审批（newapi-user-allocation D5）：列表/详情/批准/改分组/重申上游/拒绝/删除/批量刷新状态。
 * 仅管理员可操作（服务层 requireAdmin 校验 + @Audited 留痕）。
 * NewAPI 分组为 String（如 default/vip，由 NewUI 配置，决定可访问渠道/模型；对应 nas 的 40/41/42 角色组）。
 */
@RestController
@RequestMapping("/admin/newapi-registrations")
@RequiredArgsConstructor
public class NewApiRegistrationController {

    private final NewApiRegistrationService newApiRegistrationService;

    /** 审批队列列表（可选 status 过滤） */
    @GetMapping
    public ApiResponse<List<NewApiRegistrationListItem>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(newApiRegistrationService.list(status));
    }

    /** 详情（合并 NewAPI 实时状态，404 翻 NOT_FOUND） */
    @GetMapping("/{id}")
    public ApiResponse<NewApiRegistrationDetail> getDetail(@PathVariable Long id) {
        return ApiResponse.success(newApiRegistrationService.getDetail(id));
    }

    /** 批准并开通（选 NewAPI group 分组） */
    @PostMapping("/{id}/approve")
    public ApiResponse<NewApiRegistrationApproveResponse> approve(@PathVariable Long id,
                                                                  @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.success(newApiRegistrationService.approve(id, group(request)));
    }

    /** 改分组（已 APPROVED，PUT /api/user/ body 含 id+username+group，不改 quota） */
    @PostMapping("/{id}/reapprove")
    public ApiResponse<NewApiRegistrationApproveResponse> reapprove(@PathVariable Long id,
                                                                    @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.success(newApiRegistrationService.reapprove(id, group(request)));
    }

    /** 重申上游（NOT_FOUND，用新密码重建用户） */
    @PostMapping("/{id}/reprovision")
    public ApiResponse<NewApiRegistrationApproveResponse> reprovision(@PathVariable Long id,
                                                                      @Valid @RequestBody ReprovisionRequest request) {
        return ApiResponse.success(newApiRegistrationService.reprovision(id, request.getPassword(), request.getGroup()));
    }

    /** 拒绝（填原因，名额不退，用户名释放） */
    @PostMapping("/{id}/reject")
    public ApiResponse<NewApiRegistrationListItem> reject(@PathVariable Long id,
                                                          @RequestBody(required = false) RejectRequest request) {
        String reason = (request == null) ? null : request.getRejectReason();
        return ApiResponse.success(newApiRegistrationService.reject(id, reason));
    }

    /** 删除记录（不删 NewAPI 用户） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        newApiRegistrationService.delete(id);
        return ApiResponse.success();
    }

    /** 批量刷新已开通用户状态（404 翻 NOT_FOUND） */
    @PostMapping("/refresh-statuses")
    public ApiResponse<Map<String, Integer>> refreshStatuses() {
        return ApiResponse.success(newApiRegistrationService.refreshAllStatuses());
    }

    private static String group(ActionRequest request) {
        return (request == null) ? null : request.getGroup();
    }

    /** 批准/改分组请求：group 可空（=无分组） */
    @Data
    public static class ActionRequest {
        /** NewAPI 分组（如 default/vip），null=无分组 */
        private String group;
    }

    @Data
    public static class ReprovisionRequest {
        @NotBlank
        @Size(min = 8, max = 128)
        private String password;

        private String group;
    }

    @Data
    public static class RejectRequest {
        @Size(max = 255)
        private String rejectReason;
    }
}
