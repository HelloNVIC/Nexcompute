package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 用户/课题组资源配额（platform-improvements 任务 5.1）
 * 每容器可配置上限，可空=不限。有效配额 = 用户 -> 课题组 -> 主机容量回退。
 */
@Entity
@Table(name = "resource_quota")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** USER / GROUP */
    @Column(nullable = false, length = 10)
    private String scope;

    /** 用户 ID 或课题组 ID */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** CPU 核数上限（可空=不限） */
    @Column(name = "max_cpu_cores")
    private Float maxCpuCores;

    /** 内存 MB 上限（可空=不限） */
    @Column(name = "max_memory_mb")
    private Long maxMemoryMb;

    /** GPU 显存 MB 上限（可空=不限） */
    @Column(name = "max_gpu_memory_mb")
    private Long maxGpuMemoryMb;

    /** /dev/shm MB 上限（可空=不限） */
    @Column(name = "max_shm_mb")
    private Long maxShmMb;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public boolean isUserScope() {
        return "USER".equals(scope);
    }
}
