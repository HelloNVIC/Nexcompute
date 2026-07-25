package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 存储池迁移记录（任务 8.5）
 */
@Entity
@Table(name = "storage_pool_migration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoragePoolMigration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "source_instance_id", nullable = false)
    private Long sourceInstanceId;

    @Column(name = "target_instance_id", nullable = false)
    private Long targetInstanceId;

    @Column(name = "transfer_id", nullable = false, length = 64)
    private String transferId;

    @Column(nullable = false, length = 20)
    private String status; // PENDING / TRANSFERRING / COMPLETED / FAILED / CONFIRMED

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
