package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.ResourceQuota;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.ResourceQuotaRepository;
import com.nexcompute.management.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ResourceQuotaService 单元测试（platform-improvements 任务 5.6）
 * 验证有效配额回退：用户 -> 课题组 -> 主机容量。
 */
@ExtendWith(MockitoExtension.class)
class ResourceQuotaServiceTest {

    @Mock
    private ResourceQuotaRepository quotaRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PhysicalInstanceRepository instanceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ResourceQuotaService quotaService;

    private ResourceQuota userQuota(Float cpu, Long mem, Long gpu, Long shm) {
        return ResourceQuota.builder()
                .scope("USER").ownerId(1L)
                .maxCpuCores(cpu).maxMemoryMb(mem).maxGpuMemoryMb(gpu).maxShmMb(shm)
                .build();
    }

    private ResourceQuota groupQuota(Long mem) {
        return ResourceQuota.builder()
                .scope("GROUP").ownerId(10L)
                .maxMemoryMb(mem).build();
    }

    @Test
    void effectiveQuota_userWinsOverGroupAndHost() {
        // 用户配额优先：CPU 取用户 4 核（非组/主机）
        when(quotaRepository.findByScopeAndOwnerId("USER", 1L))
                .thenReturn(Optional.of(userQuota(4f, null, null, null)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                User.builder().id(1L).groupId(10L).build()));
        // 课题组也有配额（内存），不应覆盖用户的 CPU
        when(quotaRepository.findByScopeAndOwnerId("GROUP", 10L))
                .thenReturn(Optional.of(groupQuota(8192L)));

        ResourceQuotaService.EffectiveQuota q = quotaService.getEffectiveQuota(1L, 1L);

        assertThat(q.getMaxCpuCores()).isEqualTo(4f);          // 用户配额
        assertThat(q.getMaxMemoryMb()).isEqualTo(8192L);       // 课题组回退（用户未设内存）
        assertThat(q.getMaxGpuMemoryMb()).isNull();            // 均无 GPU 配额，且无主机容量
    }

    @Test
    void effectiveQuota_fallsBackToHostCapacity() {
        // 用户与课题组均无配额 -> 回退主机容量（内存 bytes -> MB，GPU MiB -> MB）
        when(quotaRepository.findByScopeAndOwnerId("USER", 1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                User.builder().id(1L).groupId(10L).build()));
        when(quotaRepository.findByScopeAndOwnerId("GROUP", 10L)).thenReturn(Optional.empty());
        // 主机最近心跳快照：memoryTotal(字节) + gpuInfo.memoryTotal(MiB)
        String status = "{\"memoryTotal\":17179869184,\"gpuInfo\":{\"name\":\"RTX 4090\",\"memoryTotal\":24576}}";
        PhysicalInstance instance = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("ONLINE").lastStatus(status).build();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(instance));

        ResourceQuotaService.EffectiveQuota q = quotaService.getEffectiveQuota(1L, 1L);

        assertThat(q.getMaxMemoryMb()).isEqualTo(16384L);     // 16GiB -> 16384 MiB
        assertThat(q.getMaxGpuMemoryMb()).isEqualTo(24576L); // GPU 24576 MiB
        assertThat(q.getMaxCpuCores()).isNull();              // 心跳未上报 CPU 核数
    }

    @Test
    void upsertQuota_invalidScope_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        quotaService.upsertQuota("TENANT", 1L,
                                new ResourceQuotaService.QuotaFields(4f, null, null, null)))
                .isInstanceOf(com.nexcompute.management.common.BusinessException.class);
    }
}
