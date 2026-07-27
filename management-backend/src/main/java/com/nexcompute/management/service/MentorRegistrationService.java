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
 * 导师邀请注册链接管理（管理员创建 MENTOR 类型链接，导师凭令牌注册并建组）。
 * 与学生注册链接（导师创建、加入既有课题组）区分：导师链接 group_id 为空，注册时创建课题组。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentorRegistrationService {

    public static final String LINK_TYPE_MENTOR = "MENTOR";

    private final RegistrationLinkRepository registrationLinkRepository;

    /** 管理员创建导师邀请链接 */
    @Audited(action = "MENTOR_REGISTRATION_LINK_CREATE", targetType = "REGISTRATION_LINK", targetIdExpr = "#result.token")
    @Transactional
    public RegistrationLink createMentorLink(int remainingCount, Instant expireAt) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可创建导师邀请链接");
        }
        RegistrationLink link = RegistrationLink.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .groupId(null)
                .creatorId(SecurityUtils.getCurrentUserId())
                .remainingCount(remainingCount)
                .expireAt(expireAt)
                .status("ACTIVE")
                .linkType(LINK_TYPE_MENTOR)
                .build();
        link = registrationLinkRepository.save(link);
        log.info("[MentorRegLink] 管理员 {} 创建导师邀请链接: token={}, 次数={}, 过期={}",
                SecurityUtils.getCurrentUserId(), link.getToken(), remainingCount, expireAt);
        return link;
    }

    /** 管理员查看所有导师邀请链接 */
    @Transactional(readOnly = true)
    public List<RegistrationLink> listMentorLinks() {
        requireAdmin();
        return registrationLinkRepository.findByLinkTypeOrderByCreatedAtDesc(LINK_TYPE_MENTOR);
    }

    /** 作废导师邀请链接 */
    @Audited(action = "MENTOR_REGISTRATION_LINK_REVOKE", targetType = "REGISTRATION_LINK", targetIdExpr = "#token")
    @Transactional
    public void revokeMentorLink(String token) {
        requireAdmin();
        RegistrationLink link = registrationLinkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));
        if (!LINK_TYPE_MENTOR.equals(link.getLinkType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该链接非导师邀请链接");
        }
        link.setStatus("REVOKED");
        registrationLinkRepository.save(link);
        log.info("[MentorRegLink] 导师邀请链接已作废: {}", token);
    }

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }
}
