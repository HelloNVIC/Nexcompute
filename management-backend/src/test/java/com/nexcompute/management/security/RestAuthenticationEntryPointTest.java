package com.nexcompute.management.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RestAuthenticationEntryPoint 单元测试（agent-defaults：未认证请求统一 401 + 业务码 1003）。
 */
class RestAuthenticationEntryPointTest {

    private RestAuthenticationEntryPoint entryPoint;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        entryPoint = new RestAuthenticationEntryPoint(new ObjectMapper());
        response = new MockHttpServletResponse();
    }

    @Test
    void commenceShouldReturn401WithTokenInvalidCode() throws Exception {
        entryPoint.commence(new MockHttpServletRequest(), response,
                new BadCredentialsException("expired"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType() != null && response.getContentType().startsWith("application/json"),
                "Content-Type 应为 application/json: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":1003"), "响应体应含业务码 1003: " + body);
        assertTrue(body.contains("令牌无效或已过期"), "响应体应含 TOKEN_INVALID 消息: " + body);
    }
}
