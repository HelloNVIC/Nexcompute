package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 受控端 OTA 升级任务记录（D7）。
 * 管理员按实例下发 agent.upgrade 后持久化任务状态，单实例失败不阻断其他实例。
 */
@Entity
@Table(name = "agent_upgrade_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentUpgradeTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "instance_number", length = 50)
    private String instanceNumber;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(length = 64)
    private String md5;

    /** PENDING / SUCCESS / FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** 当前升级阶段：downloading/verifying/backing_up/replacing/waiting（D2） */
    @Column(name = "progress_stage", length = 20)
    private String progressStage;

    /** 各段百分比 JSON：{"downloading":45,"verifying":0,"backing_up":0,"replacing":0,"waiting":0}（D2） */
    @Column(name = "stage_percents", columnDefinition = "TEXT")
    private String stagePercents;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
