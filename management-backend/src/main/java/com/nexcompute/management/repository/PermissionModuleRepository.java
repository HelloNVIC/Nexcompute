package com.nexcompute.management.repository;

import com.nexcompute.management.domain.PermissionModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PermissionModuleRepository extends JpaRepository<PermissionModule, Long> {

    Optional<PermissionModule> findByCode(String code);
}
