package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.NewApiRegisterFormMeta;
import com.nexcompute.management.dto.NewApiRegistrationSubmitResponse;
import com.nexcompute.management.dto.NewApiUsernameCheckResult;
import com.nexcompute.management.service.NewApiRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 公开 NewAPI 注册端点（newapi-user-allocation D5）：凭邀请令牌提交 NewAPI 用户注册申请。
 * permitAll（SecurityConfig 放行 /newapi-allocation/register/**），不调 NewAPI，写 PENDING 行。
 */
@RestController
@RequestMapping("/newapi-allocation/register")
@RequiredArgsConstructor
public class NewApiPublicRegisterController {

    private final NewApiRegistrationService newApiRegistrationService;

    /** GET 表单元数据：校验 token（不消耗名额），返回 label/剩余/过期供前端渲染 */
    @GetMapping
    public ApiResponse<NewApiRegisterFormMeta> getForm(@RequestParam String token) {
        return ApiResponse.success(newApiRegistrationService.validateForForm(token));
    }

    /** GET 用户名可用性检查：输入用户名后 onBlur 实时调用（本地占用 + NewAPI 查重） */
    @GetMapping("/check-username")
    public ApiResponse<NewApiUsernameCheckResult> checkUsername(@RequestParam String username) {
        return ApiResponse.success(newApiRegistrationService.checkUsername(username));
    }

    /** POST 提交注册申请：校验 -> 占用查重 -> 原子消耗名额 -> AES 加密密码 -> 写 PENDING 行 */
    @PostMapping
    public ApiResponse<NewApiRegistrationSubmitResponse> submit(@Valid @RequestBody NewApiRegisterRequest request) {
        return ApiResponse.success(newApiRegistrationService.submit(
                request.getToken(), request.getUsername(), request.getDisplayName(),
                request.getEmail(), request.getPhone(), request.getPassword()));
    }

    @Data
    public static class NewApiRegisterRequest {
        @NotBlank
        private String token;

        @NotBlank
        @Size(max = 64)
        private String username;

        @NotBlank
        @Size(max = 255)
        private String displayName;

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
