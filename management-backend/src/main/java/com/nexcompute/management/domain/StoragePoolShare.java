package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "storage_pool_share")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoragePoolShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "shared_to_user_id", nullable = false)
    private Long sharedToUserId;

    @CreationTimestamp
    @Column(name = "granted_at", updatable = false)
    private Instant grantedAt;
}
