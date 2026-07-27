package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 系统配置键值表（audit.enabled 等开关）。
 */
@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    @Column(name = "config_key", length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Column(length = 255)
    private String description;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
