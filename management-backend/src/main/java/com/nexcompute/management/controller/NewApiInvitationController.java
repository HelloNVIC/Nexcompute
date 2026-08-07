package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NewApiInvitationResponse;
import com.nexcompute.management.service.NewApiInvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 管理员 NewAPI 邀请管理（newapi-user-allocation D5）：创建/列表/详情/撤销。
 * 仅管理员可操作（服务层 requireAdmin 校验 + @Audited 留痕）。
 */
@RestController
@RequestMapping("/admin/newapi-invitations")
@RequiredArgsConstructor
public class NewApiInvitationController {

    private final NewApiInvitationService newApiInvitationService;

    /** 创建邀请 */
    @PostMapping
    public ApiResponse<NewApiInvitationResponse> create(@Valid @RequestBody CreateNewApiInvitationRequest request) {
        return ApiResponse.success(newApiInvitationService.create(
                request.getLabel(), request.getMaxUses(), request.getExpireAt()));
    }

    /** 列表 */
    @GetMapping
    public ApiResponse<List<NewApiInvitationResponse>> list() {
        return ApiResponse.success(newApiInvitationService.list());
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<NewApiInvitationResponse> get(@PathVariable Long id) {
        return ApiResponse.success(newApiInvitationService.get(id));
    }

    /** 撤销 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<NewApiInvitationResponse> revoke(@PathVariable Long id) {
        return ApiResponse.success(newApiInvitationService.revoke(id));
    }

    @Data
    public static class CreateNewApiInvitationRequest {
        @NotBlank
        @Size(max = 128)
        private String label;

        @NotNull
        @Min(1)
        private Integer maxUses;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant expireAt;
    }
}
