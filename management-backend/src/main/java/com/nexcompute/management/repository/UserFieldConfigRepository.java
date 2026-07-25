package com.nexcompute.management.repository;

import com.nexcompute.management.domain.UserFieldConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserFieldConfigRepository extends JpaRepository<UserFieldConfig, Short> {

    default Optional<UserFieldConfig> findSingleton() {
        return findById((short) 1);
    }
}
