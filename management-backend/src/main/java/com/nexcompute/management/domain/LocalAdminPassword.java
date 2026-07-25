package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 全局受控端管理密码（platform-refinements）。
 * 所有受控端共用同一个明文密码，单行配置（id 恒为 1）。
 * 用户明确要求明文传输与保存，接受该风险。
 */
@Entity
@Table(name = "local_admin_password")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalAdminPassword {

    @Id
    @Column(name = "id")
    private Short id; // 恒为 1（单行）

    @Column(nullable = false, length = 255)
    private String password;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
