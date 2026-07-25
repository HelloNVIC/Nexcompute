package com.nexcompute.management.repository;

import com.nexcompute.management.domain.RegistrationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationLinkRepository extends JpaRepository<RegistrationLink, Long> {

    Optional<RegistrationLink> findByToken(String token);

    List<RegistrationLink> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);

    List<RegistrationLink> findByGroupId(Long groupId);
}
