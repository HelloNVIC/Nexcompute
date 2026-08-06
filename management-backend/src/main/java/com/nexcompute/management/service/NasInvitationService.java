package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NasProperties;
import com.nexcompute.management.domain.NasInvitation;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.NasInvitationResponse;
import com.nexcompute.management.repository.NasInvitationRepository;
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
 * NAS 邀请令牌管理（nas-allocation D5/D2）。
 * 管理员创建/列表/详情/撤销；token 用 SecureRandom 生成不可猜测串，返回完整注册链接。
 * 名额原子消耗见 {@link NasRegistrationService#submit}（经 {@link NasInvitationRepository#redeem} 条件 UPDATE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NasInvitationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final NasInvitationRepository invitationRepository;
    private final NasProperties nasProperties;

    /** 管理员创建邀请（SecureRandom token，回填 created_by，拼完整注册链接） */
    @Audited(action = "NAS_INVITATION_CREATE", targetType = "NAS_INVITATION", targetIdExpr = "#result.token")
    @Transactional
    public NasInvitationResponse create(String label, Integer maxUses, Instant expireAt) {
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
        NasInvitation invitation = NasInvitation.builder()
                .token(generateToken())
                .label(label)
                .maxUses(maxUses)
                .expiresAt(expireAt)
                .createdBy(SecurityUtils.getCurrentUserId())
                .build();
        invitation = invitationRepository.save(invitation);
        log.info("[NAS-Invitation] 管理员 {} 创建邀请: id={}, label={}, maxUses={}, expire={}",
                SecurityUtils.getCurrentUserId(), invitation.getId(), label, maxUses, expireAt);
        return toResponse(invitation);
    }

    /** 管理员查看所有邀请 */
    @Transactional(readOnly = true)
    public List<NasInvitationResponse> list() {
        requireAdmin();
        return invitationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /** 管理员查看邀请详情 */
    @Transactional(readOnly = true)
    public NasInvitationResponse get(Long id) {
        requireAdmin();
        return toResponse(requireInvitation(id));
    }

    /** 管理员撤销邀请（幂等：已撤销直接返回） */
    @Audited(action = "NAS_INVITATION_REVOKE", targetType = "NAS_INVITATION", targetIdExpr = "#id")
    @Transactional
    public NasInvitationResponse revoke(Long id) {
        requireAdmin();
        NasInvitation invitation = requireInvitation(id);
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(Instant.now());
            invitationRepository.save(invitation);
            log.info("[NAS-Invitation] 邀请已撤销: id={}", id);
        }
        return toResponse(invitation);
    }

    /**
     * 拼完整注册链接：{当前管理端地址}/nas-register?token={token}。
     * 管理端地址按优先级取：浏览器 Origin 头（dev 跨域）-> Referer 头 origin（生产同域）
     * -> 请求 Host（fromContextRequest）-> nasProperties.portalBaseUrl 配置回退。
     * 不再用固定配置，确保管理员从哪个地址访问就生成哪个地址的链接。
     */
    public String buildRegisterUrl(String token) {
        String base = resolveCurrentBase();
        return base + "/nas-register?token=" + token;
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
        String base = nasProperties.getPortalBaseUrl();
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
    public NasInvitation getByToken(String token) {
        return invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_INVITATION_INVALID));
    }

    private NasInvitation requireInvitation(Long id) {
        return invitationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_INVITATION_NOT_FOUND));
    }

    private NasInvitationResponse toResponse(NasInvitation inv) {
        return new NasInvitationResponse(
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
