package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 受控端连接凭证（任务 4.1）
 */
@Entity
@Table(name = "agent_credential")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", unique = true)
    private Long instanceId;

    @Column(nullable = false, unique = true, length = 128)
    private String token;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Boolean revoked;
}
