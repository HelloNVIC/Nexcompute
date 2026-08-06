package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * NAS 注册申请（nas-allocation D2）。
 * 凭邀请令牌公开提交：AES-GCM 加密密码暂存（password_enc），不调 TrueNAS。
 * 管理员批准时解密 -> TrueNAS 建用户 -> 回填 id/uid -> 擦密码 -> APPROVED。
 * 5 态机见 {@link NasRegistrationStatus}。密码在 APPROVED/REJECTED/pending 过期后擦除，FAILED 保留可重试。
 */
@Entity
@Table(name = "nas_registration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NasRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所用邀请（nas_invitation.id） */
    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    /** TrueNAS 用户名（POSIX 字符集，PENDING/APPROVED/FAILED 占用，REJECTED 释放） */
    @Column(nullable = false, length = 64)
    private String username;

    /** 姓名（传给 TrueNAS full_name） */
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /** 邮箱 */
    @Column(nullable = false, length = 255)
    private String email;

    /** 手机号 */
    @Column(nullable = false, length = 64)
    private String phone;

    /** AES-GCM 加密密码密文（base64-urlsafe(nonce12 + ct + tag)，开通/拒绝/pending 过期后擦除） */
    @Column(name = "password_enc", columnDefinition = "text")
    private String passwordEnc;

    /** 状态：PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = NasRegistrationStatus.PENDING;

    /** TrueNAS 用户 ID（开通后回填） */
    @Column(name = "truenas_user_id")
    private Integer truenasUserId;

    /** TrueNAS UID */
    @Column(name = "truenas_uid")
    private Integer truenasUid;

    /** 提交时间（pending 过期扫描据此判定） */
    @CreationTimestamp
    @Column(name = "submitted_at", updatable = false)
    private Instant submittedAt;

    /** 审核人（管理员 app_user.id） */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    /** 审核时间 */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** 拒绝原因 */
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    /** 开通失败错误信息（FAILED/NOT_FOUND 重试时记录） */
    @Column(name = "provision_error", columnDefinition = "text")
    private String provisionError;
}
