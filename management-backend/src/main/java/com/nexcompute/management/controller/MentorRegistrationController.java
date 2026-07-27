package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.service.MentorRegistrationService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 管理员导师邀请注册链接管理（创建/查看/作废 MENTOR 类型链接）。
 * 仅管理员可操作（服务层强制 ADMIN 校验）。
 */
@RestController
@RequestMapping("/admin/mentor-registration-links")
@RequiredArgsConstructor
public class MentorRegistrationController {

    private final MentorRegistrationService mentorRegistrationService;

    /** 创建导师邀请链接 */
    @PostMapping
    public ApiResponse<RegistrationLink> create(@RequestBody CreateMentorLinkRequest request) {
        return ApiResponse.success(mentorRegistrationService.createMentorLink(
                request.getRemainingCount(), request.getExpireAt()));
    }

    /** 查看所有导师邀请链接 */
    @GetMapping
    public ApiResponse<List<RegistrationLink>> list() {
        return ApiResponse.success(mentorRegistrationService.listMentorLinks());
    }

    /** 作废导师邀请链接 */
    @PostMapping("/{token}/revoke")
    public ApiResponse<Void> revoke(@PathVariable String token) {
        mentorRegistrationService.revokeMentorLink(token);
        return ApiResponse.success();
    }

    @Data
    public static class CreateMentorLinkRequest {
        @Min(1)
        private int remainingCount;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant expireAt;
    }
}
