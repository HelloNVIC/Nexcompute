package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * email-notification D6：邮件发送日志（每次发送结果追踪，供管理员排查送达）。
 * status=SUCCESS/FAILED；测试发送 trigger_key 为 null。
 */
@Entity
@Table(name = "email_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_key", length = 50)
    private String triggerKey;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_email", length = 100)
    private String recipientEmail;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 20)
    private String status; // SUCCESS / FAILED

    @Column(columnDefinition = "TEXT")
    private String error;

    @CreationTimestamp
    @Column(name = "sent_at", updatable = false)
    private Instant sentAt;
}
