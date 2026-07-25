package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.ResourceQuota;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.ResourceQuotaRepository;
import com.nexcompute.management.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 资源配额服务（platform-improvements 任务 5.2）
 * 管理员 CRUD 用户/课题组配额；有效配额 = 用户 -> 课题组 -> 主机容量回退。
 * 配额为每容器可配置上限（软约束 + 后端校验），非跨容器聚合预算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceQuotaService {

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_GROUP = "GROUP";

    private final ResourceQuotaRepository quotaRepository;
    private final UserRepository userRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;

    @Audited(action = "QUOTA_UPSERT", targetType = "RESOURCE_QUOTA", targetIdExpr = "#result.id")
    @Transactional
    public ResourceQuota upsertQuota(String scope, Long ownerId, QuotaFields fields) {
        if (!SCOPE_USER.equals(scope) && !SCOPE_GROUP.equals(scope)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配额 scope 必须为 USER 或 GROUP");
        }
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配额 owner_id 不能为空");
        }
        ResourceQuota quota = quotaRepository.findByScopeAndOwnerId(scope, ownerId)
                .orElseGet(() -> ResourceQuota.builder().scope(scope).ownerId(ownerId).build());
        quota.setMaxCpuCores(fields.maxCpuCores);
        quota.setMaxMemoryMb(fields.maxMemoryMb);
        quota.setMaxGpuMemoryMb(fields.maxGpuMemoryMb);
        quota.setMaxShmMb(fields.maxShmMb);
        return quotaRepository.save(quota);
    }

    /** 查询指定用户/课题组配额 */
    public Optional<ResourceQuota> getQuota(String scope, Long ownerId) {
        return quotaRepository.findByScopeAndOwnerId(scope, ownerId);
    }

    @Audited(action = "QUOTA_DELETE", targetType = "RESOURCE_QUOTA", targetIdExpr = "#id")
    @Transactional
    public void deleteQuota(Long id) {
        quotaRepository.deleteById(id);
    }

    /**
     * 计算用户在目标物理实例上的有效配额（任务 5.2 spec: 用户 -> 课题组 -> 主机容量）。
     * 各维度独立回退：该维度用户配额未设则取课题组，再未设则取主机容量，皆无则不限（null）。
     */
    public EffectiveQuota getEffectiveQuota(Long userId, Long instanceId) {
        ResourceQuota userQuota = quotaRepository.findByScopeAndOwnerId(SCOPE_USER, userId).orElse(null);

        ResourceQuota groupQuota = null;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getGroupId() != null) {
            groupQuota = quotaRepository.findByScopeAndOwnerId(SCOPE_GROUP, user.getGroupId()).orElse(null);
        }

        HostCapacity host = readHostCapacity(instanceId);

        return EffectiveQuota.builder()
                .maxCpuCores(firstNonNull(
                        val(userQuota, ResourceQuota::getMaxCpuCores),
                        val(groupQuota, ResourceQuota::getMaxCpuCores),
                        null)) // 心跳未上报 CPU 核数，无主机容量回退
                .maxMemoryMb(firstNonNull(
                        val(userQuota, ResourceQuota::getMaxMemoryMb),
                        val(groupQuota, ResourceQuota::getMaxMemoryMb),
                        host.memoryMb))
                .maxGpuMemoryMb(firstNonNull(
                        val(userQuota, ResourceQuota::getMaxGpuMemoryMb),
                        val(groupQuota, ResourceQuota::getMaxGpuMemoryMb),
                        host.gpuMemoryMb))
                .maxShmMb(firstNonNull(
                        val(userQuota, ResourceQuota::getMaxShmMb),
                        val(groupQuota, ResourceQuota::getMaxShmMb),
                        null)) // SHM 无主机容量概念，无回退
                .build();
    }

    private <T> T val(ResourceQuota q, java.util.function.Function<ResourceQuota, T> getter) {
        return q == null ? null : getter.apply(q);
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /** 从物理实例最近心跳快照读取主机容量（内存/GPU 显存 -> MB） */
    private HostCapacity readHostCapacity(Long instanceId) {
        if (instanceId == null) return new HostCapacity(null, null);
        PhysicalInstance instance = instanceRepository.findById(instanceId).orElse(null);
        if (instance == null || instance.getLastStatus() == null) return new HostCapacity(null, null);
        try {
            JsonNode status = objectMapper.readTree(instance.getLastStatus());
            // 系统内存：gopsutil 上报为字节 -> MB
            Long memBytes = longVal(status, "memoryTotal");
            // GPU 显存：nvidia-smi 上报为 MiB -> 直接作 MB（1 MiB ≈ 1.048575 MB，配额为软上限可接受）
            Long gpuMemMb = null;
            if (status.has("gpuInfo") && !status.get("gpuInfo").isNull()) {
                gpuMemMb = longVal(status.get("gpuInfo"), "memoryTotal");
            }
            return new HostCapacity(
                    memBytes == null ? null : memBytes / (1024 * 1024),
                    gpuMemMb);
        } catch (Exception e) {
            log.warn("[Quota] 解析主机容量失败: {}", e.getMessage());
            return new HostCapacity(null, null);
        }
    }

    private Long longVal(JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isNumber()) {
            return node.get(field).asLong();
        }
        return null;
    }

    /** 配额字段（均可空=不限） */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class QuotaFields {
        private Float maxCpuCores;
        private Long maxMemoryMb;
        private Long maxGpuMemoryMb;
        private Long maxShmMb;
    }

    /** 有效配额（每维可空=不限） */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class EffectiveQuota {
        private Float maxCpuCores;
        private Long maxMemoryMb;
        private Long maxGpuMemoryMb;
        private Long maxShmMb;
    }

    private record HostCapacity(Long memoryMb, Long gpuMemoryMb) {}
}
