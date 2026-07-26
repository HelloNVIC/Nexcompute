package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 工单（任务 12.1）
 */
@Entity
@Table(name = "ticket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    /** 工单唯一编号（platform-refinements #3，如 TK20260724-000001） */
    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private TicketType type;

    @Column(nullable = false)
    private String content;

    @Column(name = "submitter_id", nullable = false)
    private Long submitterId;

    @Column(name = "submitter_name", nullable = false, length = 100)
    private String submitterName;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(nullable = false, length = 20)
    private String status; // PENDING / CLOSED

    /** 联系方式（platform-env-ota-realtime D11：默认账户手机号，可改） */
    @Column(length = 100)
    private String contact;

    private String reply;

    @Column(name = "replier_id")
    private Long replierId;

    @Column(name = "replier_name", length = 100)
    private String replierName;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
