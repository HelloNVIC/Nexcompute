package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * email-notification D3：用户邮件偏好（仅记用户主动关闭的可关闭项）。
 * <p>mandatory 触发键（注册/禁用）不入此表，查询时强制视为 true。
 */
@Entity
@Table(name = "user_email_pref",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_email_pref_user_trigger",
                columnNames = {"user_id", "trigger_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEmailPref {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trigger_key", nullable = false, length = 50)
    private String triggerKey;

    @Column(nullable = false)
    private Boolean enabled;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
