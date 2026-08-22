package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PasswordResetOtp;
import com.nexcompute.management.repository.PasswordResetOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 忘记密码验证码校验失败计数落盘（password-management-and-id-validation，与 NasProvisionFailureRecorder 同模式）。
 * <p>{@code AuthService.resetPassword} 为 {@code @Transactional}，校验码不匹配时若在同事务内
 * {@code setAttemptCount+1; save(); throw}，会因事务回滚而丢失 attempt_count 递增（provision-failure-rollback pitfall），
 * 致每码 5 次校验上限失效（count 永不持久化）。本组件以 {@link Propagation#REQUIRES_NEW} 在独立事务中递增计数，
 * 提交后再抛出业务异常，确保 spec「每码 5 次校验上限达上限作废」生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetOtpRecorder {

    private final PasswordResetOtpRepository otpRepository;

    /**
     * 在独立事务中记录一次校验失败：attempt_count+1 落库；达上限则置 consumed_at 作废。
     * 调用方在外层事务（含读操作）抛出 BusinessException 触发外层回滚，但本事务已独立提交。
     *
     * @return 更新后的 attempt_count（调用方据此判断抛 INVALID 还是 TOO_MANY_ATTEMPTS）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailedAttempt(Long otpId, int maxAttempts) {
        PasswordResetOtp otp = otpRepository.findById(otpId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_RESET_OTP_NOT_FOUND));
        otp.setAttemptCount(otp.getAttemptCount() + 1);
        if (otp.getAttemptCount() >= maxAttempts) {
            otp.setConsumedAt(Instant.now());
        }
        otpRepository.save(otp);
        return otp.getAttemptCount();
    }
}
