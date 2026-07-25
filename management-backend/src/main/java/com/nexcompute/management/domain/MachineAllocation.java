package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 机器分配关系（导师将物理实例分配给学生）（任务 3.1）
 * 一台机器可分配给多个学生（共享）。
 */
@Entity
@Table(name = "machine_allocation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MachineAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "allocated_by", nullable = false)
    private Long allocatedBy;

    /**
     * 该学生在该实例上单容器内存上限（MB，可空=不限）。
     * platform-refinements：导师为每学生+每实例设的单容器内存上限，容器创建时校验不得超过。
     */
    @Column(name = "per_container_memory_mb")
    private Integer perContainerMemoryMb;

    // platform-refinements #2：展示用（被分配学生姓名）
    @Transient
    private String studentName;

    @CreationTimestamp
    @Column(name = "allocated_at", updatable = false)
    private Instant allocatedAt;
}
