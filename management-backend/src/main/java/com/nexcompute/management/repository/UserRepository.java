package com.nexcompute.management.repository;

import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 按工号（studentId）精准查用户（platform-refinements 6.6：镜像按工号共享） */
    Optional<User> findByStudentId(String studentId);

    List<User> findByGroupId(Long groupId);

    Page<User> findByRole(UserRole role, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.role = com.nexcompute.management.domain.UserRole.STUDENT AND u.groupId = :groupId")
    List<User> findStudentsByGroupId(Long groupId);
}
