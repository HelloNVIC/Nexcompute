package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NasInvitationResponse;
import com.nexcompute.management.service.NasInvitationService;
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
 * 管理员 NAS 邀请管理（nas-allocation D5）：创建/列表/详情/撤销。
 * 仅管理员可操作（服务层 requireAdmin 校验 + @Audited 留痕）。
 */
@RestController
@RequestMapping("/admin/nas-invitations")
@RequiredArgsConstructor
public class NasInvitationController {

    private final NasInvitationService nasInvitationService;

    /** 创建邀请 */
    @PostMapping
    public ApiResponse<NasInvitationResponse> create(@Valid @RequestBody CreateNasInvitationRequest request) {
        return ApiResponse.success(nasInvitationService.create(
                request.getLabel(), request.getMaxUses(), request.getExpireAt()));
    }

    /** 列表 */
    @GetMapping
    public ApiResponse<List<NasInvitationResponse>> list() {
        return ApiResponse.success(nasInvitationService.list());
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<NasInvitationResponse> get(@PathVariable Long id) {
        return ApiResponse.success(nasInvitationService.get(id));
    }

    /** 撤销 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<NasInvitationResponse> revoke(@PathVariable Long id) {
        return ApiResponse.success(nasInvitationService.revoke(id));
    }

    @Data
    public static class CreateNasInvitationRequest {
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
