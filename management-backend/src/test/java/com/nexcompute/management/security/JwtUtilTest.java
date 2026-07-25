package com.nexcompute.management.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单元测试（任务 14.1）
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // 至少 256 位的密钥（32 字节）
        String secret = "test-secret-key-for-jwt-token-signing-min-256-bits-long-enough!!";
        jwtUtil = new JwtUtil(secret, 3600000L);
    }

    @Test
    void generateToken_containsCorrectClaims() {
        String token = jwtUtil.generateToken(42L, "alice", "ADMIN");

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username")).isEqualTo("alice");
        assertThat(claims.get("role")).isEqualTo("ADMIN");
    }

    @Test
    void validateToken_valid_returnsTrue() {
        String token = jwtUtil.generateToken(1L, "user", "STUDENT");
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_invalid_returnsFalse() {
        assertThat(jwtUtil.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    void validateToken_expired_returnsFalse() throws InterruptedException {
        JwtUtil shortLived = new JwtUtil(
                "test-secret-key-for-jwt-token-signing-min-256-bits-long-enough!!", 1L);
        String token = shortLived.generateToken(1L, "user", "STUDENT");
        Thread.sleep(50);
        assertThat(shortLived.validateToken(token)).isFalse();
    }

    @Test
    void extractUserId_returnsCorrectId() {
        String token = jwtUtil.generateToken(123L, "bob", "MENTOR");
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(123L);
    }

    @Test
    void extractUsername_returnsCorrectUsername() {
        String token = jwtUtil.generateToken(1L, "charlie", "ADMIN");
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("charlie");
    }

    @Test
    void extractRole_returnsCorrectRole() {
        String token = jwtUtil.generateToken(1L, "user", "STUDENT");
        assertThat(jwtUtil.extractRole(token)).isEqualTo("STUDENT");
    }
}
