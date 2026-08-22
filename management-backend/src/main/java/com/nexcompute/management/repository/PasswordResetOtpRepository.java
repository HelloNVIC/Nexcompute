package com.nexcompute.management.repository;

import com.nexcompute.management.domain.PasswordResetOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 忘记密码验证码仓库（password-management-and-id-validation D2/D12）。
 * <ul>
 *   <li>{@link #findFirstByUsernameOrderByCreatedAtDesc}：取该用户名最近一行验证码（含已消费/已过期，
 *       由 service 判定状态），用于重置校验与 send-code 限频回查。</li>
 *   <li>{@link #existsByUsernameAndCreatedAtAfter}：send-code 限频，判定冷却窗口内是否已建码。</li>
 *   <li>{@link #deleteByExpiresAtBefore}：过期/已消费码清理调度（D9）批量删除。</li>
 * </ul>
 */
@Repository
public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    /** 取该用户名最近一行验证码（按 created_at 倒序），含已消费/已过期，由 service 判定状态 */
    Optional<PasswordResetOtp> findFirstByUsernameOrderByCreatedAtDesc(String username);

    /** send-code 限频：判定该用户名在 createdAt 之后是否已建码（冷却窗口内不重复建码） */
    boolean existsByUsernameAndCreatedAtAfter(String username, Instant createdAt);

    @Modifying
    @Query("DELETE FROM PasswordResetOtp o WHERE o.expiresAt < :before")
    int deleteByExpiresAtBefore(Instant before);
}
