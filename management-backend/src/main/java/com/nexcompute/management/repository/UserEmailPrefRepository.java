package com.nexcompute.management.repository;

import com.nexcompute.management.domain.UserEmailPref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserEmailPrefRepository extends JpaRepository<UserEmailPref, Long> {

    Optional<UserEmailPref> findByUserIdAndTriggerKey(Long userId, String triggerKey);

    List<UserEmailPref> findByUserId(Long userId);
}
