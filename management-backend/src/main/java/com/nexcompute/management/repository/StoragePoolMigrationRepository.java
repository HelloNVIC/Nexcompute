package com.nexcompute.management.repository;

import com.nexcompute.management.domain.StoragePoolMigration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoragePoolMigrationRepository extends JpaRepository<StoragePoolMigration, Long> {

    Optional<StoragePoolMigration> findByPoolId(Long poolId);
}
