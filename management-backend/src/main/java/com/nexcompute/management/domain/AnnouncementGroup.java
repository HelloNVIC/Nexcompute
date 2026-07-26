package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 公告-课题组 多对多关联（D10）。
 * targetScope=GROUP 时由单值 targetId 改为多对多，旧数据已由 Flyway 回填。
 */
@Entity
@Table(name = "announcement_group",
        uniqueConstraints = @UniqueConstraint(columnNames = {"announcement_id", "group_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
