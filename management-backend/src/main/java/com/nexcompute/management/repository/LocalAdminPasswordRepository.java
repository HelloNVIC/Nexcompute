package com.nexcompute.management.repository;

import com.nexcompute.management.domain.LocalAdminPassword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocalAdminPasswordRepository extends JpaRepository<LocalAdminPassword, Short> {

    /** 单行配置，id 恒为 1。 */
    default Optional<LocalAdminPassword> findGlobal() {
        return findById((short) 1);
    }
}
