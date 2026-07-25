package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 镜像元数据（任务 9.1）
 */
@Entity
@Table(name = "image_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 100)
    private String tag;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_name", length = 100)
    private String ownerName;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "tar_path", length = 500)
    private String tarPath;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "source_container", length = 100)
    private String sourceContainer;

    @Column(length = 64)
    private String checksum;

    @Column(nullable = false, length = 20)
    private String status; // UPLOADING / READY / FAILED

    /**
     * 应用端口列表（int[]，来自 tar ExposedPorts 解析，platform-improvements 任务 3.1）。
     * 供容器创建时自动预填"容器内端口"。可空。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "app_ports", columnDefinition = "jsonb")
    private List<Integer> appPorts;

    /** 使用说明（上传/提交时填写）。 */
    @Column(name = "usage_instructions", columnDefinition = "text")
    private String usageInstructions;

    /** 备注（commit 镜像时的备注）。 */
    @Column(name = "note", length = 500)
    private String note;

    /** 所属项目（commit 镜像时的项目名）。 */
    @Column(name = "project", length = 200)
    private String project;

    /** 来源工号（commit 镜像时提交者的工号）。 */
    @Column(name = "source_worker_id", length = 100)
    private String sourceWorkerId;

    /**
     * 可见性：PRIVATE（仅本人+管理员）/ SHARED_TO_ALL（全用户可见，管理员上传）/ SHARED（已显式共享）。
     * 公共镜像（isPublic=true）同时为 SHARED_TO_ALL 并走公共镜像库自动同步。
     */
    @Column(nullable = false, length = 20)
    private String visibility;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public String getRef() {
        return name + ":" + tag;
    }
}
