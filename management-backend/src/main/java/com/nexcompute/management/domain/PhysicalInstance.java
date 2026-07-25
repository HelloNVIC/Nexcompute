package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.JsonJdbcType;

import java.time.Instant;

/**
 * 物理实例（受控端）（任务 4.1、6.1）
 */
@Entity
@Table(name = "physical_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_number", nullable = false, unique = true, length = 50)
    private String instanceNumber;

    @Column(name = "machine_name", length = 100)
    private String machineName;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "os_info", length = 200)
    private String osInfo;

    @Column(name = "gpu_info", length = 500)
    private String gpuInfo;

    @Column(name = "connect_mode", nullable = false, length = 20)
    private String connectMode; // direct / tunnel

    @Column(nullable = false, length = 20)
    private String status; // ONLINE / OFFLINE

    @Column(name = "agent_version", length = 50)
    private String agentVersion;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_status", columnDefinition = "jsonb")
    private String lastStatus; // JSON 快照

    @Column(name = "local_admin_password_hash", length = 255)
    private String localAdminPasswordHash;

    @Column(name = "storage_root", length = 500)
    private String storageRoot;

    /** MAC（platform-refinements #1：硬件指纹） */
    @Column(length = 50)
    private String mac;

    /** 机器码（platform-refinements #1：硬件指纹） */
    @Column(name = "machine_code", length = 200)
    private String machineCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public boolean isOnline() {
        return "ONLINE".equals(status);
    }
}
