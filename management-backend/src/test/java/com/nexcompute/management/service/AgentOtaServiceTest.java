package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.agent.OtaProgressTracker;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.repository.AgentUpgradeTaskRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTA 升级版本号归一化 + 超时配置验证（platform-audit-logging-ux）。
 * 版本号不加 'v' 前缀（与受控端心跳上报的纯数字版本对齐）；超时 180s 与属性默认对齐。
 */
@ExtendWith(MockitoExtension.class)
class AgentOtaServiceTest {

    @Mock private NexcomputeProperties properties;
    @Mock private PhysicalInstanceRepository instanceRepository;
    @Mock private AgentUpgradeTaskRepository taskRepository;
    @Mock private AgentCommandService agentCommandService;
    @Mock private OtaProgressTracker otaProgressTracker;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AgentOtaService agentOtaService;

    @Test
    void sanitizeVersion_stripsLeadingV() {
        assertThat(agentOtaService.sanitizeVersion("v0.2.0")).isEqualTo("0.2.0");
        assertThat(agentOtaService.sanitizeVersion("V0.2.0")).isEqualTo("0.2.0");
    }

    @Test
    void sanitizeVersion_keepsBareNumeric() {
        assertThat(agentOtaService.sanitizeVersion("0.2.0")).isEqualTo("0.2.0");
        assertThat(agentOtaService.sanitizeVersion("1.0.0-beta")).isEqualTo("1.0.0-beta");
    }

    @Test
    void sanitizeVersion_handlesNullAndBlank() {
        assertThat(agentOtaService.sanitizeVersion(null)).isEmpty();
        assertThat(agentOtaService.sanitizeVersion("   ")).isEmpty();
    }

    @Test
    void sanitizeVersion_stripsNonVersionChars() {
        assertThat(agentOtaService.sanitizeVersion("v 0.2.0!")).isEqualTo("0.2.0");
    }
}
