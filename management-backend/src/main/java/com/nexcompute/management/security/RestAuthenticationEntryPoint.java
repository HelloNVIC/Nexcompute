package com.nexcompute.management.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证请求入口点（agent-defaults：登录失效 401 修复）。
 * 此前 SecurityConfig 未配置 exceptionHandling，未认证请求（无/过期 token）落入
 * Spring Security 默认 Http403ForbiddenEntryPoint（403 + 空 body），前端只弹
 * "无权限执行此操作"不登出不跳转。现统一返回 HTTP 401 + 业务码 1003，
 * 与 GlobalExceptionHandler 的 TOKEN_INVALID 响应形状一致，前端既有 401 处理路径即可接管。
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.TOKEN_INVALID)));
    }
}
