package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AuditService 审计开关 + force 恒记验证（platform-audit-logging-ux 2.10）。
 * 开关关闭且 force=false -> 不记；开关关闭但 force=true -> 仍记（开关切换恒审计护栏）。
 * （@Async 在纯 Mockito 单测中不生效，方法直接同步执行，验证业务逻辑。）
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private AuditSwitchService auditSwitchService;

    @InjectMocks
    private AuditService auditService;

    private AuditContext ctx() {
        return AuditContext.builder()
                .operatorId(1L).operatorName("admin").operatorRole("ADMIN")
                .clientInfo("ua=x; ip=127.0.0.1").ipAddress("127.0.0.1")
                .build();
    }

    @Test
    void record_skipsWhenSwitchOffAndNotForced() {
        when(auditSwitchService.isAuditEnabled()).thenReturn(false);

        auditService.record("TEST", "T", "1", "c", true, null, ctx(), false);

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void record_forcesEvenWhenSwitchOff() {
        // force=true 短路开关检查（isAuditEnabled 不被调用），恒记
        auditService.record("AUDIT_TOGGLE", "SYSTEM_CONFIG", null, "关闭审计", true, null, ctx(), true);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("AUDIT_TOGGLE");
        assertThat(saved.getOperatorId()).isEqualTo(1L);
        assertThat(saved.getOperatorRole()).isEqualTo("ADMIN");
        assertThat(saved.getOperationNo()).isNotNull();
    }

    @Test
    void record_recordsWhenSwitchOn() {
        when(auditSwitchService.isAuditEnabled()).thenReturn(true);

        auditService.record("ANY", "T", "1", "c", true, null, ctx(), false);

        verify(auditLogRepository).save(any());
    }
}
