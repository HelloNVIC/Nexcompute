package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 学生注册链接（任务 3.1）
 * UUID + 可用次数 + 过期时间，两者皆可触发失效。导师可主动作废。
 */
@Entity
@Table(name = "registration_link")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token; // UUID

    @Column(name = "group_id", nullable = false)
    private Long groupId; // 关联课题组（即导师课题组）

    @Column(name = "creator_id", nullable = false)
    private Long creatorId; // 创建者（导师）用户 ID

    @Column(name = "remaining_count", nullable = false)
    private Integer remainingCount; // 剩余可用次数

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt; // 过期时间

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE / REVOKED / EXHAUSTED / EXPIRED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** 是否有效：状态为 ACTIVE 且未过期且次数 > 0 */
    public boolean isValid() {
        return "ACTIVE".equals(status)
                && remainingCount > 0
                && expireAt != null
                && expireAt.isAfter(Instant.now());
    }

    public boolean isExpired() {
        return expireAt != null && expireAt.isBefore(Instant.now());
    }
}
