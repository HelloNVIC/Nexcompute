package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 忘记密码验证码（password-management-and-id-validation D2）。
 * 用户凭工号/学号请求发送验证码：生成 6 位数字码并 BCrypt 哈希存 code_hash（不存明文），
 * 异步发至绑定邮箱；重置时校验哈希匹配、未过期、未消费、未达尝试上限。
 */
@Entity
@Table(name = "password_reset_otp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** 是否已过期（expires_at 早于当前时刻） */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /** 是否已消费（重置成功或达尝试上限作废后置 consumed_at） */
    public boolean isConsumed() {
        return consumedAt != null;
    }

    /** 校验失败次数是否已达上限 */
    public boolean isAttemptsExhausted(int max) {
        return attemptCount != null && attemptCount >= max;
    }
}
