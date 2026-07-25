package com.nexcompute.management.repository;

import com.nexcompute.management.domain.PermissionMatrix;
import com.nexcompute.management.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionMatrixRepository extends JpaRepository<PermissionMatrix, Long> {

    List<PermissionMatrix> findByRole(UserRole role);

    Optional<PermissionMatrix> findByRoleAndModuleId(UserRole role, Long moduleId);
}
