package com.nexcompute.management.repository;

import com.nexcompute.management.domain.Container;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContainerRepository extends JpaRepository<Container, Long> {

    Optional<Container> findByName(String name);

    List<Container> findByOwnerId(Long ownerId);

    List<Container> findByInstanceId(Long instanceId);

    List<Container> findByStoragePoolId(Long storagePoolId);

    List<Container> findByOwnerIdIn(List<Long> ownerIds);

    /** 查找使用某存储池且运行中的容器 */
    List<Container> findByStoragePoolIdAndStatus(Long storagePoolId, String status);

    /** 该实例的容器数（instance-identity：删除前占用检查） */
    long countByInstanceId(Long instanceId);
}
