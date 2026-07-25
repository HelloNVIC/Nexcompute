package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 端口分配（任务 10.2）
 */
@Entity
@Table(name = "port_allocation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "container_id")
    private Long containerId;

    @Column(name = "container_port", nullable = false)
    private Integer containerPort;

    @Column(name = "host_port", nullable = false)
    private Integer hostPort;

    @CreationTimestamp
    @Column(name = "allocated_at", updatable = false)
    private Instant allocatedAt;
}
