package com.nexcompute.management.audit;

import com.nexcompute.management.domain.SystemConfig;
import com.nexcompute.management.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 审计开关服务验证（platform-audit-logging-ux 2.8 / 2.10）。
 * 默认开；读取 "false" 视为关闭；切换时持久化。force 恒记由 AuditService 验证。
 */
@ExtendWith(MockitoExtension.class)
class AuditSwitchServiceTest {

    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private AuditSwitchService auditSwitchService;

    @Test
    void isEnabled_defaultsTrueWhenNoConfig() {
        when(systemConfigRepository.findById("audit.enabled")).thenReturn(Optional.empty());
        assertThat(auditSwitchService.isAuditEnabled()).isTrue();
    }

    @Test
    void isEnabled_readsFalseWhenDisabled() {
        SystemConfig cfg = SystemConfig.builder().configKey("audit.enabled").configValue("false").build();
        when(systemConfigRepository.findById("audit.enabled")).thenReturn(Optional.of(cfg));
        assertThat(auditSwitchService.isAuditEnabled()).isFalse();
    }

    @Test
    void setEnabled_persistsAndReturns() {
        when(systemConfigRepository.findById("audit.enabled")).thenReturn(Optional.empty());
        when(systemConfigRepository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = auditSwitchService.setAuditEnabled(false);

        assertThat(result).isFalse();
        verify(systemConfigRepository).save(argThat(c -> "false".equals(c.getConfigValue())));
    }
}
