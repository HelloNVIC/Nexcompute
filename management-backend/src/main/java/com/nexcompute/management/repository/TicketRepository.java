package com.nexcompute.management.repository;

import com.nexcompute.management.domain.Ticket;
import com.nexcompute.management.domain.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findBySubmitterIdOrderByCreatedAtDesc(Long submitterId);

    /** 该用户提交的工单数（V38 用户删除占用检查） */
    long countBySubmitterId(Long submitterId);

    List<Ticket> findByGroupIdOrderByCreatedAtDesc(Long groupId);

    List<Ticket> findByStatusOrderByCreatedAtDesc(String status);

    List<Ticket> findByTypeOrderByCreatedAtDesc(TicketType type);
}
