package com.nexcompute.management.repository;

import com.nexcompute.management.domain.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    Page<EmailLog> findByRecipientUserIdOrderBySentAtDesc(Long recipientUserId, Pageable pageable);

    Page<EmailLog> findByTriggerKeyOrderBySentAtDesc(String triggerKey, Pageable pageable);

    Page<EmailLog> findByStatusOrderBySentAtDesc(String status, Pageable pageable);

    Page<EmailLog> findAllByOrderBySentAtDesc(Pageable pageable);
}
