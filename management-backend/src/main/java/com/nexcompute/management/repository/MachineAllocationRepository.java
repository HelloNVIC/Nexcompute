package com.nexcompute.management.repository;

import com.nexcompute.management.domain.MachineAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MachineAllocationRepository extends JpaRepository<MachineAllocation, Long> {

    List<MachineAllocation> findByUserId(Long userId);

    List<MachineAllocation> findByInstanceId(Long instanceId);

    List<MachineAllocation> findByGroupId(Long groupId);

    boolean existsByInstanceIdAndUserId(Long instanceId, Long userId);

    /** 按 实例+学生 查找分配（platform-refinements 8.2/8.3：upsert 与内存上限校验） */
    Optional<MachineAllocation> findByInstanceIdAndUserId(Long instanceId, Long userId);

    /** 课题组维度的分配记录（platform-refinements #4：展示每组已分配资源） */
    @Query("select m from MachineAllocation m where m.groupId is not null")
    List<MachineAllocation> findGroupAllocations();

    /** 由某用户分配的记录（platform-refinements #2：导师"已分配机器"显示自己分配的） */
    List<MachineAllocation> findByAllocatedBy(Long allocatedBy);
}
