package com.nexcompute.management.service;

import com.nexcompute.management.domain.PortAllocation;
import com.nexcompute.management.repository.PortAllocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PortAllocationService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class PortAllocationServiceTest {

    @Mock
    private PortAllocationRepository allocationRepository;

    @InjectMocks
    private PortAllocationService portAllocationService;

    @Test
    void allocatePort_firstPort_returns30000() {
        PortAllocation saved = PortAllocation.builder()
                .id(1L).instanceId(1L).containerPort(22).hostPort(30000).build();
        when(allocationRepository.findByInstanceId(1L)).thenReturn(java.util.List.of());
        when(allocationRepository.save(any())).thenReturn(saved);

        PortAllocation result = portAllocationService.allocatePort(1L, 22);

        assertThat(result.getHostPort()).isEqualTo(30000);
        assertThat(result.getContainerPort()).isEqualTo(22);
    }

    @Test
    void allocatePort_avoidsConflict() {
        PortAllocation existing = PortAllocation.builder()
                .instanceId(1L).containerPort(22).hostPort(30000).build();
        PortAllocation saved = PortAllocation.builder()
                .id(2L).instanceId(1L).containerPort(8888).hostPort(30001).build();
        when(allocationRepository.findByInstanceId(1L)).thenReturn(java.util.List.of(existing));
        when(allocationRepository.save(any())).thenReturn(saved);

        PortAllocation result = portAllocationService.allocatePort(1L, 8888);

        assertThat(result.getHostPort()).isEqualTo(30001); // 跳过已用的 30000
    }
}
