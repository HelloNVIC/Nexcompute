package com.nexcompute.management.service;

import com.nexcompute.management.config.PasswordResetProperties;
import com.nexcompute.management.repository.PasswordResetOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 忘记密码验证码过期清理调度（password-management-and-id-validation D9），复用平台既有 @Scheduled 调度。
 * 每 nexcompute.password-reset.cleanup-interval-hours（默认 6）删除 expires_at < now - retain-days（默认 7）的行
 * （含已消费/已过期，单条 bulk DELETE，幂等不阻塞业务，表不过度膨胀）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetScheduler {

    private final PasswordResetOtpRepository otpRepository;
    private final PasswordResetProperties properties;

    @Scheduled(fixedDelayString = "${nexcompute.password-reset.cleanup-interval-hours:6}h")
    @Transactional
    public void cleanupExpired() {
        int retainDays = properties.getCleanupRetainDays();
        if (retainDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retainDays, ChronoUnit.DAYS);
        int deleted = otpRepository.deleteByExpiresAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[PasswordReset-Cleanup] 清理过期/已消费验证码: 删除 {} 条, cutoff={}", deleted, cutoff);
        } else {
            log.debug("[PasswordReset-Cleanup] 无命中, cutoff={}", cutoff);
        }
    }
}
