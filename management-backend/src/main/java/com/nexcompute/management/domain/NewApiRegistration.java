package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * NewAPI 注册申请（newapi-user-allocation D2）。
 * 凭邀请令牌公开提交：AES-GCM 加密密码暂存（password_enc），不调 NewAPI。
 * 管理员批准时解密 -> NewAPI 建用户 -> search 回查拿 id 回填 -> 擦密码 -> APPROVED。
 * 5 态机见 {@link NewApiRegistrationStatus}。密码在 APPROVED/REJECTED/pending 过期后擦除，FAILED 保留可重试。
 * AES 密钥复用既有 PASSWORD_ENC_KEY（与 nas 共用同一密钥与密文格式，D7）。
 */
@Entity
@Table(name = "newapi_registration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewApiRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所用邀请（newapi_invitation.id） */
    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    /** NewAPI 用户名（PENDING/APPROVED/FAILED 占用，REJECTED 释放） */
    @Column(nullable = false, length = 64)
    private String username;

    /** 显示名（传给 NewAPI display_name） */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** 邮箱 */
    @Column(nullable = false, length = 255)
    private String email;

    /** 手机号（仅本地存，NewAPI 用户对象无 phone 字段） */
    @Column(nullable = false, length = 64)
    private String phone;

    /** AES-GCM 加密密码密文（base64-urlsafe(nonce12 + ct + tag)，开通/拒绝/pending 过期后擦除；与 nas 共用密钥与格式） */
    @Column(name = "password_enc", columnDefinition = "text")
    private String passwordEnc;

    /** 状态：PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = NewApiRegistrationStatus.PENDING;

    /** NewAPI 用户 ID（开通后经 search 回查回填，建用户响应无 id） */
    @Column(name = "newapi_user_id")
    private Integer newapiUserId;

    /** NewAPI 分组（如 default/vip，决定可访问渠道/模型；对应 nas 的 40/41/42 角色组） */
    @Column(name = "newapi_group", length = 64)
    private String newapiGroup;

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
