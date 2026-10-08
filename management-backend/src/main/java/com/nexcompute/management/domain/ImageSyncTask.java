package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 镜像同步任务（V36：批次内每台物理实例一行）
 */
@Entity
@Table(name = "image_sync_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageSyncTask {

    /** 任务状态常量 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PULLING = "PULLING";
    public static final String STATUS_PULLED = "PULLED";
    public static final String STATUS_ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_OFFLINE_SKIPPED = "OFFLINE_SKIPPED";

    /** 终态集合（用于判定批次是否可 DONE） */
    public static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
            STATUS_PULLED, STATUS_ALREADY_EXISTS, STATUS_FAILED, STATUS_TIMEOUT, STATUS_OFFLINE_SKIPPED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "instance_number", nullable = false, length = 50)
    private String instanceNumber;

    @Column(name = "command_id", length = 64)
    private String commandId;

    @Column(nullable = false, length = 20)
    private String status; // PENDING/PULLING/PULLED/ALREADY_EXISTS/FAILED/TIMEOUT/OFFLINE_SKIPPED

    @Column
    private Integer percent;

    @Column(name = "last_text", length = 500)
    private String lastText;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(status);
    }
}
