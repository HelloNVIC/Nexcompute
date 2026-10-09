package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * NewAPI 邀请令牌（newapi-user-allocation D2）。
 * 管理员创建的 NewAPI 用户注册邀请：标签/名额/有效期/撤销。
 * 名额消耗由 {@code NewApiInvitationRepository.redeem} 经单条条件 UPDATE 原子完成（D8）。
 */
@Entity
@Table(name = "newapi_invitation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewApiInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 邀请令牌（SecureRandom 生成，不可猜测） */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** 标签（便于管理员识别邀请用途） */
    @Column(nullable = false, length = 128)
    private String label;

    /** 最大使用次数（名额上限） */
    @Builder.Default
    @Column(name = "max_uses", nullable = false)
    private Integer maxUses = 1;

    /** 已使用次数（原子条件 UPDATE 递增，并发不超发） */
    @Builder.Default
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    /** 过期时间 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 创建者（管理员 app_user.id） */
    @Column(name = "created_by") // V38 改可空：创建者删除后置空
    private Long createdBy;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** 撤销时间（空表示有效） */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 是否已撤销 */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 是否已过期 */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /** 是否有效：未撤销 + 未过期 + 名额未耗尽 */
    public boolean isValid() {
        return !isRevoked() && !isExpired() && usedCount < maxUses;
    }

    /** 剩余可用次数 */
    public int getRemaining() {
        return Math.max(0, maxUses - usedCount);
    }
}
