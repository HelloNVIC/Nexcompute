package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NewApiProperties;
import com.nexcompute.management.domain.NewApiInvitation;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.NewApiInvitationResponse;
import com.nexcompute.management.repository.NewApiInvitationRepository;
import com.nexcompute.management.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * NewAPI 邀请令牌管理（newapi-user-allocation D5/D2）。
 * 管理员创建/列表/详情/撤销；token 用 SecureRandom 生成不可猜测串，返回完整注册链接。
 * 名额原子消耗见 {@link NewApiRegistrationService#submit}（经 {@link NewApiInvitationRepository#redeem} 条件 UPDATE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewApiInvitationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final NewApiInvitationRepository invitationRepository;
    private final NewApiProperties newApiProperties;

    /** 管理员创建邀请（SecureRandom token，回填 created_by，拼完整注册链接） */
    @Audited(action = "NEWAPI_INVITATION_CREATE", targetType = "NEWAPI_INVITATION", targetIdExpr = "#result.token")
    @Transactional
    public NewApiInvitationResponse create(String label, Integer maxUses, Instant expireAt) {
        requireAdmin();
        if (label == null || label.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "label 必填");
        }
        if (label.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "label 过长（<=128）");
        }
        if (maxUses == null || maxUses < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxUses 须 >= 1");
        }
        if (expireAt == null || expireAt.isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expireAt 须为未来时间");
        }
        NewApiInvitation invitation = NewApiInvitation.builder()
                .token(generateToken())
                .label(label)
                .maxUses(maxUses)
                .expiresAt(expireAt)
                .createdBy(SecurityUtils.getCurrentUserId())
                .build();
        invitation = invitationRepository.save(invitation);
        log.info("[NewAPI-Invitation] 管理员 {} 创建邀请: id={}, label={}, maxUses={}, expire={}",
                SecurityUtils.getCurrentUserId(), invitation.getId(), label, maxUses, expireAt);
        return toResponse(invitation);
    }

    /** 管理员查看所有邀请 */
    @Transactional(readOnly = true)
    public List<NewApiInvitationResponse> list() {
        requireAdmin();
        return invitationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /** 管理员查看邀请详情 */
    @Transactional(readOnly = true)
    public NewApiInvitationResponse get(Long id) {
        requireAdmin();
        return toResponse(requireInvitation(id));
    }

    /** 管理员撤销邀请（幂等：已撤销直接返回） */
    @Audited(action = "NEWAPI_INVITATION_REVOKE", targetType = "NEWAPI_INVITATION", targetIdExpr = "#id")
    @Transactional
    public NewApiInvitationResponse revoke(Long id) {
        requireAdmin();
        NewApiInvitation invitation = requireInvitation(id);
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(Instant.now());
            invitationRepository.save(invitation);
            log.info("[NewAPI-Invitation] 邀请已撤销: id={}", id);
        }
        return toResponse(invitation);
    }

    /**
     * 拼完整注册链接：{当前管理端地址}/newapi-register?token={token}。
     * 管理端地址按优先级取：浏览器 Origin 头（dev 跨域）-> Referer 头 origin（生产同域）
     * -> 请求 Host -> newApiProperties.portalBaseUrl 配置回退。
     */
    public String buildRegisterUrl(String token) {
        String base = resolveCurrentBase();
        return base + "/newapi-register?token=" + token;
    }

    private String resolveCurrentBase() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest req = attrs == null ? null : attrs.getRequest();
        if (req != null) {
            String origin = req.getHeader("Origin");
            if (origin != null && !origin.isBlank()) {
                return stripTrailingSlash(origin);
            }
            String referer = req.getHeader("Referer");
            if (referer != null && !referer.isBlank()) {
                String base = originFromUrl(referer);
                if (base != null) return base;
            }
            try {
                UriComponents uc = ServletUriComponentsBuilder.fromCurrentRequest().build();
                String scheme = uc.getScheme();
                String host = uc.getHost();
                if (scheme != null && host != null) {
                    int port = uc.getPort();
                    StringBuilder sb = new StringBuilder().append(scheme).append("://").append(host);
                    if (port > 0 && !isDefaultPort(scheme, port)) {
                        sb.append(":").append(port);
                    }
                    return sb.toString();
                }
            } catch (Exception ignored) {
                // 无请求上下文，走配置回退
            }
        }
        String base = newApiProperties.getPortalBaseUrl();
        return (base == null || base.isBlank()) ? "" : stripTrailingSlash(base);
    }

    private static String originFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            int port = uri.getPort();
            StringBuilder sb = new StringBuilder().append(scheme).append("://").append(host);
            if (port > 0 && !isDefaultPort(scheme, port)) {
                sb.append(":").append(port);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    private static String stripTrailingSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }

    /** 凭 token 取邀请（注册服务兑换/校验用） */
    @Transactional(readOnly = true)
    public NewApiInvitation getByToken(String token) {
        return invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NEWAPI_INVITATION_INVALID));
    }

    private NewApiInvitation requireInvitation(Long id) {
        return invitationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NEWAPI_INVITATION_NOT_FOUND));
    }

    private NewApiInvitationResponse toResponse(NewApiInvitation inv) {
        return new NewApiInvitationResponse(
                inv.getId(),
                inv.getToken(),
                inv.getLabel(),
                inv.getMaxUses(),
                inv.getUsedCount(),
                inv.getRemaining(),
                inv.getExpiresAt(),
                inv.getCreatedAt(),
                inv.getRevokedAt(),
                buildRegisterUrl(inv.getToken()),
                inv.isValid()
        );
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }
}
