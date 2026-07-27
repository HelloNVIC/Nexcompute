package com.nexcompute.management.audit;

import com.nexcompute.management.domain.SystemConfig;
import com.nexcompute.management.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计开关缓存服务（D12：audit.enabled 默认开；缓存减少写库前查询开销）。
 * 关闭时拦截器与 @Audited 跳过写库；force=true 不受开关影响恒记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSwitchService {

    public static final String KEY_AUDIT_ENABLED = "audit.enabled";
    private static final boolean DEFAULT_ENABLED = true;

    private final SystemConfigRepository systemConfigRepository;

    @Cacheable(value = "systemConfig", key = "'audit.enabled'")
    @Transactional(readOnly = true)
    public boolean isAuditEnabled() {
        try {
            return systemConfigRepository.findById(KEY_AUDIT_ENABLED)
                    .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                    .orElse(DEFAULT_ENABLED);
        } catch (Exception e) {
            log.warn("[Audit] 读取审计开关失败，按默认 {} 处理：{}", DEFAULT_ENABLED, e.getMessage());
            return DEFAULT_ENABLED;
        }
    }

    @CacheEvict(value = "systemConfig", key = "'audit.enabled'")
    @Transactional
    public boolean setAuditEnabled(boolean enabled) {
        SystemConfig cfg = systemConfigRepository.findById(KEY_AUDIT_ENABLED)
                .orElseGet(() -> SystemConfig.builder()
                        .configKey(KEY_AUDIT_ENABLED)
                        .description("审计开关：true 记录，false 停记（开关切换恒审计）")
                        .build());
        cfg.setConfigValue(Boolean.toString(enabled));
        systemConfigRepository.save(cfg);
        return enabled;
    }
}
