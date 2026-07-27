package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.EmailTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * email-notification D3：用户邮件偏好端点（学生/导师可逐项关闭 5 类可关闭提醒；
 * 注册/禁用两项 mandatory，返回 mandatory=true 供前端 disabled）。
 */
@RestController
@RequestMapping("/me/email-prefs")
@RequiredArgsConstructor
public class EmailPrefController {

    private final EmailTriggerService emailTriggerService;

    /** GET /me/email-prefs：当前用户偏好视图（含 mandatory 标志） */
    @GetMapping
    public ApiResponse<List<EmailTriggerService.PrefView>> get() {
        return ApiResponse.success(emailTriggerService.userPrefViews(SecurityUtils.getCurrentUserId()));
    }

    /**
     * PUT /me/email-prefs：批量更新用户偏好。请求体为 {triggerKey: enabled} 映射；
     * mandatory 触发键由 {@link EmailTriggerService#setUserEnabled} 拒绝。
     */
    @PutMapping
    public ApiResponse<List<EmailTriggerService.PrefView>> update(@RequestBody Map<String, Boolean> request) {
        Long userId = SecurityUtils.getCurrentUserId();
        for (Map.Entry<String, Boolean> e : request.entrySet()) {
            try {
                EmailTrigger t = EmailTrigger.valueOf(e.getKey());
                emailTriggerService.setUserEnabled(t, userId, Boolean.TRUE.equals(e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未知触发键: " + e.getKey());
            }
        }
        return ApiResponse.success(emailTriggerService.userPrefViews(userId));
    }
}
