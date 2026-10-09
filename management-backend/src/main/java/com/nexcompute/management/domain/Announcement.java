package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 公告（任务 13.1）
 */
@Entity
@Table(name = "announcement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private String content;

    @Column(name = "target_scope", nullable = false, length = 20)
    private String targetScope; // ALL / GROUP / ROLE

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_role", length = 20)
    private String targetRole;

    @Column(name = "publish_mode", nullable = false, length = 20)
    private String publishMode; // IMMEDIATE / SCHEDULED

    @Column(name = "publish_at")
    private Instant publishAt;

    @Column(nullable = false, length = 20)
    private String status; // PENDING / PUBLISHED

    @Column(name = "author_id") // V38 改可空：作者删除后置空，展示回退 author_name
    private Long authorId;

    @Column(name = "author_name", length = 100)
    private String authorName;

    /**
     * GROUP 多选课题组 ID 集合（D10，非持久化，由 service 填充）。
     * 旧单值 targetId 已迁移至 announcement_group，对 GROUP 仅以此集合判定可见性。
     */
    @Transient
    private java.util.List<Long> targetGroupIds;

    /** 课题组名集合（D10，非持久化，列表展示用） */
    @Transient
    private java.util.List<String> targetGroupNames;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
