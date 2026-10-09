package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 镜像同步批次（V36：一次"同步到所有机器"操作）
 */
@Entity
@Table(name = "image_sync_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageSyncBatch {

    /** 批次状态常量 */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    @Column(name = "image_ref", nullable = false, length = 500)
    private String imageRef;

    @Column(name = "initiated_by") // V38 改可空：发起人删除后置空
    private Long initiatedBy;

    @Column(nullable = false, length = 20)
    private String status; // RUNNING / DONE

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
