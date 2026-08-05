package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.RegistrationLinkRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理员邀请注册链接管理（管理员创建 ADMIN 类型链接，被邀请人凭令牌注册为管理员）。
 * 与导师邀请（{@link MentorRegistrationService}）区分：管理员链接注册产物 role=ADMIN、无课题组，
 * 注册表单复用学生注册字段（姓名/工号/密码/邮箱/手机）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRegistrationService {

    public static final String LINK_TYPE_ADMIN = "ADMIN";

    private final RegistrationLinkRepository registrationLinkRepository;

    /** 管理员创建管理员邀请链接 */
    @Audited(action = "ADMIN_REGISTRATION_LINK_CREATE", targetType = "REGISTRATION_LINK", targetIdExpr = "#result.token")
    @Transactional
    public RegistrationLink createAdminLink(int remainingCount, Instant expireAt) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可创建管理员邀请链接");
        }
        RegistrationLink link = RegistrationLink.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .groupId(null)
                .creatorId(SecurityUtils.getCurrentUserId())
                .remainingCount(remainingCount)
                .expireAt(expireAt)
                .status("ACTIVE")
                .linkType(LINK_TYPE_ADMIN)
                .build();
        link = registrationLinkRepository.save(link);
        log.info("[AdminRegLink] 管理员 {} 创建管理员邀请链接: token={}, 次数={}, 过期={}",
                SecurityUtils.getCurrentUserId(), link.getToken(), remainingCount, expireAt);
        return link;
    }

    /** 管理员查看所有管理员邀请链接 */
    @Transactional(readOnly = true)
    public List<RegistrationLink> listAdminLinks() {
        requireAdmin();
        return registrationLinkRepository.findByLinkTypeOrderByCreatedAtDesc(LINK_TYPE_ADMIN);
    }

    /** 作废管理员邀请链接 */
    @Audited(action = "ADMIN_REGISTRATION_LINK_REVOKE", targetType = "REGISTRATION_LINK", targetIdExpr = "#token")
    @Transactional
    public void revokeAdminLink(String token) {
        requireAdmin();
        RegistrationLink link = registrationLinkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));
        if (!LINK_TYPE_ADMIN.equals(link.getLinkType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该链接非管理员邀请链接");
        }
        link.setStatus("REVOKED");
        registrationLinkRepository.save(link);
        log.info("[AdminRegLink] 管理员邀请链接已作废: {}", token);
    }

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }
}
