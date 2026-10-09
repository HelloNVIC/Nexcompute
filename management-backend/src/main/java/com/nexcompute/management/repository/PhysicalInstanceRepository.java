package com.nexcompute.management.repository;

import com.nexcompute.management.domain.PhysicalInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PhysicalInstanceRepository extends JpaRepository<PhysicalInstance, Long> {

    Optional<PhysicalInstance> findByInstanceNumber(String instanceNumber);

    boolean existsByInstanceNumber(String instanceNumber);

    /** 按硬件指纹查找（platform-refinements #1：注册去重） */
    Optional<PhysicalInstance> findByMacAndMachineCode(String mac, String machineCode);

    /** 按 SMBIOS UUID 查找（D14：主指纹去重） */
    Optional<PhysicalInstance> findBySmbiosUuid(String smbiosUuid);

    /** 按机器码（MachineGuid）单独查找（instance-identity：SMBIOS 缺失时的身份判定，
     *  不再要求 MAC 同时匹配——MAC 采集失败不再放弃去重） */
    Optional<PhysicalInstance> findByMachineCode(String machineCode);

    List<PhysicalInstance> findByStatus(String status);
}
