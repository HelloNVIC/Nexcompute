package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NasRegisterFormMeta;
import com.nexcompute.management.dto.NasRegistrationSubmitResponse;
import com.nexcompute.management.dto.NasUsernameCheckResult;
import com.nexcompute.management.service.NasRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 公开 NAS 注册端点（nas-allocation D5）：凭邀请令牌提交 TrueNAS 用户注册申请。
 * permitAll（SecurityConfig 放行 /nas-allocation/register/**），不调 TrueNAS，写 PENDING 行。
 */
@RestController
@RequestMapping("/nas-allocation/register")
@RequiredArgsConstructor
public class NasPublicRegisterController {

    private final NasRegistrationService nasRegistrationService;

    /** GET 表单元数据：校验 token（不消耗名额），返回 label/剩余/过期供前端渲染 */
    @GetMapping
    public ApiResponse<NasRegisterFormMeta> getForm(@RequestParam String token) {
        return ApiResponse.success(nasRegistrationService.validateForForm(token));
    }

    /** GET 用户名可用性检查：输入用户名后 onBlur 实时调用（本地占用 + TrueNAS 查重） */
    @GetMapping("/check-username")
    public ApiResponse<NasUsernameCheckResult> checkUsername(@RequestParam String username) {
        return ApiResponse.success(nasRegistrationService.checkUsername(username));
    }

    /** POST 提交注册申请：校验 -> 占用查重 -> 原子消耗名额 -> AES 加密密码 -> 写 PENDING 行 */
    @PostMapping
    public ApiResponse<NasRegistrationSubmitResponse> submit(@Valid @RequestBody NasRegisterRequest request) {
        return ApiResponse.success(nasRegistrationService.submit(
                request.getToken(), request.getUsername(), request.getFullName(),
                request.getEmail(), request.getPhone(), request.getPassword()));
    }

    @Data
    public static class NasRegisterRequest {
        @NotBlank
        private String token;

        @NotBlank
        @Size(max = 64)
        private String username;

        @NotBlank
        @Size(max = 255)
        private String fullName;

        @NotBlank
        @Email
        @Size(max = 255)
        private String email;

        @NotBlank
        @Size(max = 64)
        private String phone;

        @NotBlank
        @Size(min = 8, max = 128)
        private String password;
    }
}
