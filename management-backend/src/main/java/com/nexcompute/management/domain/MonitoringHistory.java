package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 监控历史数据（任务 11.1）
 */
@Entity
@Table(name = "monitoring_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "instance_number", nullable = false, length = 50)
    private String instanceNumber;

    @Column(name = "cpu_usage")
    private Float cpuUsage;

    @Column(name = "cpu_temp")
    private Float cpuTemp;

    @Column(name = "gpu_usage")
    private Float gpuUsage;

    @Column(name = "gpu_temp")
    private Float gpuTemp;

    @Column(name = "memory_usage")
    private Float memoryUsage;

    @Column(name = "memory_total")
    private Long memoryTotal;

    @Column(name = "memory_used")
    private Long memoryUsed;

    @Column(name = "gpu_memory_total")
    private Long gpuMemoryTotal;

    @Column(name = "gpu_memory_used")
    private Long gpuMemoryUsed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "status_snapshot", columnDefinition = "jsonb")
    private String statusSnapshot;

    @CreationTimestamp
    @Column(name = "recorded_at", updatable = false)
    private Instant recordedAt;
}
