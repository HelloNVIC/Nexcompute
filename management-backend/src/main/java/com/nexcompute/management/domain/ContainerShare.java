package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 容器共享关系（platform-refinements #2：按工号共享，可限时，可取消）
 */
@Entity
@Table(name = "container_share")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_id", nullable = false)
    private Long containerId;

    @Column(name = "shared_to_user_id", nullable = false)
    private Long sharedToUserId;

    @Column(name = "shared_by", nullable = false)
    private Long sharedBy;

    /** 到期时间；空=永久 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
