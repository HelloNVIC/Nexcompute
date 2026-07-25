package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 存储池（任务 8.1）
 */
@Entity
@Table(name = "storage_pool")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoragePool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_name", nullable = false, length = 200)
    private String poolName;

    @Column(name = "project_name", nullable = false, length = 100)
    private String projectName;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "instance_number", nullable = false, length = 50)
    private String instanceNumber;

    @Column(name = "user_student_id", nullable = false, length = 50)
    private String userStudentId;

    @Column(name = "pool_path", length = 500)
    private String poolPath;

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE / MIGRATING / MIGRATED

    /**
     * 离线派生标识（计算型，不持久化）。
     * 池离线 = 所属物理实例离线 OR poolPath 缺失/无效。
     * 与 ACTIVE/MIGRATING/MIGRATED 共存展示（迁移中且离线则同时体现两标识）。
     */
    @Transient
    private Boolean offline;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
