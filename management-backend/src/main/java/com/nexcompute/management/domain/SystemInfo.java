package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 系统信息（platform-refinements #5）。单行（id=1），管理员设置，其他用户只读。
 */
@Entity
@Table(name = "system_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemInfo {

    @Id
    @Column(name = "id")
    private Short id; // 恒为 1

    private String maintainer;
    @Column(name = "maintainer_phone")
    private String maintainerPhone;
    private String owner;
    @Column(name = "owner_phone")
    private String ownerPhone;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
