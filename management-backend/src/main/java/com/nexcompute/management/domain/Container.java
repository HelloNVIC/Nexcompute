package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 容器（任务 10.1）
 */
@Entity
@Table(name = "container")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "instance_number", nullable = false, length = 50)
    private String instanceNumber;

    @Column(name = "image_ref", nullable = false, length = 200)
    private String imageRef;

    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "storage_pool_id")
    private Long storagePoolId;

    /** 项目名（任务 4：容器命名用 学号-项目名-随机串） */
    @Column(name = "project_name", length = 100)
    private String projectName;

    /** 原始表单配置快照（JSON，任务 4：容器配置页回显） */
    @Column(name = "form_snapshot", columnDefinition = "text")
    private String formSnapshot;

    @Column(name = "cpu_limit")
    private Float cpuLimit;

    @Column(name = "memory_limit")
    private Long memoryLimit;

    @Column(name = "gpu_memory_limit")
    private Integer gpuMemoryLimit;

    @Column(name = "shm_size")
    private Long shmSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "port_mappings", columnDefinition = "jsonb")
    private String portMappings;

    @Column(name = "ssh_password", length = 100)
    private String sshPassword;

    @Column(name = "docker_id", length = 100)
    private String dockerId;

    /** 备注（platform-refinements #1） */
    @Column(length = 500)
    private String remark;

    @Column(nullable = false, length = 20)
    private String status; // CREATED / RUNNING / STOPPED / EXITED / REMOVED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // platform-refinements #1/#2：展示用派生字段（非持久化）
    @Transient
    private String ownerName;

    @Transient
    private java.util.List<ShareView> sharedWith;

    /** 物理实例是否在线（platform-refinements #7：离线时前端展示"物理实例不在线"） */
    @Transient
    private Boolean instanceOnline;

    /** 共有人视图（platform-refinements #2） */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ShareView {
        private Long id;
        private Long userId;
        private String realName;
        private String workerId;
        private Instant expiresAt;
        private boolean expired;
    }

    public boolean isRunning() {
        return "RUNNING".equals(status);
    }
}
