package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.AgentUpgradeTaskRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.ImageSyncTaskRepository;
import com.nexcompute.management.repository.MachineAllocationRepository;
import com.nexcompute.management.repository.MonitoringHistoryRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.PortAllocationRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.StoragePoolMigrationRepository;
import com.nexcompute.management.repository.StoragePoolRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 物理实例管理服务（任务 6.1-6.4）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalInstanceService {

    private final PhysicalInstanceRepository instanceRepository;
    private final MachineAllocationRepository allocationRepository;
    private final ResearchGroupRepository groupRepository;
    private final AgentCommandService agentCommandService;
    private final ContainerRepository containerRepository;
    private final StoragePoolRepository poolRepository;
    private final PortAllocationRepository portAllocationRepository;
    private final StoragePoolMigrationRepository migrationRepository;
    private final ImageSyncTaskRepository imageSyncTaskRepository;
    private final AgentCredentialRepository credentialRepository;
    private final MonitoringHistoryRepository monitoringHistoryRepository;
    private final AgentUpgradeTaskRepository agentUpgradeTaskRepository;
    private final JdbcTemplate jdbcTemplate;

    public PhysicalInstance getInstance(Long id) {
        return instanceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
    }

    public List<PhysicalInstance> listAll() {
        return instanceRepository.findAll();
    }

    /** 学生/导师查看可使用的实例（platform-refinements #1：导师纳入本课题组分配的实例） */
    public List<PhysicalInstance> listAllocatedTo(Long userId) {
        Set<Long> instanceIds = new LinkedHashSet<>();
        for (var a : allocationRepository.findByUserId(userId)) {
            instanceIds.add(a.getInstanceId());
        }
        // 导师本身具有课题组内所有实例的使用权
        if (SecurityUtils.getCurrentRole() == UserRole.MENTOR) {
            groupRepository.findByMentorId(userId).ifPresent(g -> {
                for (var a : allocationRepository.findByGroupId(g.getId())) {
                    instanceIds.add(a.getInstanceId());
                }
            });
        }
        return instanceIds.stream()
                .map(id -> instanceRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 修改物理机编号（任务 6.2）
     * 校验全系统唯一，关联存储池命名同步更新（存储池命名含编号前缀）
     */
    @Audited(action = "INSTANCE_NUMBER_UPDATE", targetType = "PHYSICAL_INSTANCE", targetIdExpr = "#id")
    @Transactional
    public PhysicalInstance updateInstanceNumber(Long id, String newNumber) {
        PhysicalInstance instance = getInstance(id);
        if (instanceRepository.existsByInstanceNumber(newNumber)
                && !instance.getInstanceNumber().equals(newNumber)) {
            throw new BusinessException(ErrorCode.MACHINE_NUMBER_CONFLICT);
        }
        String oldNumber = instance.getInstanceNumber();
        instance.setInstanceNumber(newNumber);
        instance = instanceRepository.save(instance);

        // 同步存储池命名（存储池命名格式：物理机编号-工号/学号-项目名）
        // TODO: 通知受控端重命名存储池目录（任务 8.x 处理）
        log.info("[Instance] 编号修改: {} -> {} (存储池命名需同步)", oldNumber, newNumber);

        return instance;
    }

    /**
     * 远程重启（任务 6.3）
     */
    @Audited(action = "INSTANCE_RESTART", targetType = "PHYSICAL_INSTANCE", targetIdExpr = "#id")
    public AgentCommandResult restart(Long id) {
        PhysicalInstance instance = getInstance(id);
        checkOnline(instance);
        return dispatchCommand(instance, "system.restart", Map.of());
    }

    /**
     * 远程息屏（任务 6.3）
     */
    @Audited(action = "INSTANCE_SCREEN_OFF", targetType = "PHYSICAL_INSTANCE", targetIdExpr = "#id")
    public AgentCommandResult screenOff(Long id) {
        PhysicalInstance instance = getInstance(id);
        checkOnline(instance);
        return dispatchCommand(instance, "system.screen_off", Map.of());
    }

    /**
     * PowerShell 远程执行（任务 6.4）
     * 仅管理员，入审计（@Audited + 额外记录输出）
     */
    @Audited(action = "POWERSHELL_EXEC", targetType = "PHYSICAL_INSTANCE", targetIdExpr = "#id")
    public AgentCommandResult executePowerShell(Long id, String command) {
        PhysicalInstance instance = getInstance(id);
        checkOnline(instance);
        log.info("[PowerShell] 管理员对实例 {} 执行命令: {}", instance.getInstanceNumber(), command);
        return dispatchCommand(instance, "system.powershell", Map.of("command", command));
    }

    /**
     * 删除物理实例（instance-identity）。仅管理员；在线实例与被占用实例拒绝删除：
     * 占用含 用户分配/容器/存储池/端口分配/迁移记录（聚合计数报错）；镜像同步任务为纯派生
     * 记录，force=true 时跳过检查并级联删除（前端弹窗确认即强制）。级联清理 鉴权凭证/
     * 监控历史/公共镜像同步状态（休眠表，无 JPA 实体）/升级任务记录/镜像同步任务。
     */
    @Audited(action = "INSTANCE_DELETE", targetType = "PHYSICAL_INSTANCE", targetIdExpr = "#id")
    @Transactional
    public void deleteInstance(Long id, boolean force) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "删除物理实例仅管理员可执行");
        }
        PhysicalInstance instance = getInstance(id);
        // 在线拒绝：在线机器删除后会经 4001 重注册机制复活为新行
        if (agentCommandService.isAgentConnected(instance.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.CONFLICT, "实例在线（受控端已连接），请先在该机器退出受控端后再删除");
        }
        // 占用检查（聚合计数）；镜像同步任务仅在非强制时拦截
        List<String> blockers = new ArrayList<>();
        long n = allocationRepository.countByInstanceId(id);
        if (n > 0) blockers.add("已分配 " + n + " 个用户");
        n = containerRepository.countByInstanceId(id);
        if (n > 0) blockers.add(n + " 个容器");
        n = poolRepository.countByInstanceId(id);
        if (n > 0) blockers.add(n + " 个存储池");
        n = portAllocationRepository.countByInstanceId(id);
        if (n > 0) blockers.add(n + " 条端口分配");
        n = migrationRepository.countBySourceInstanceIdOrTargetInstanceId(id, id);
        if (n > 0) blockers.add(n + " 条迁移记录");
        if (!force) {
            n = imageSyncTaskRepository.countByInstanceId(id);
            if (n > 0) blockers.add(n + " 条镜像同步任务（可在确认删除时强制清除）");
        }
        if (!blockers.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "实例被占用，无法删除：" + String.join("、", blockers));
        }
        // 级联清理（镜像同步任务为派生记录，force 与否都随实例删除）
        credentialRepository.deleteByInstanceId(id);
        monitoringHistoryRepository.deleteByInstanceId(id);
        jdbcTemplate.update("DELETE FROM public_image_sync WHERE instance_id = ?", id);
        agentUpgradeTaskRepository.deleteByInstanceId(id);
        imageSyncTaskRepository.deleteByInstanceId(id);
        instanceRepository.delete(instance);
        log.info("[Instance] 已删除物理实例: number={} id={} force={}", instance.getInstanceNumber(), id, force);
    }

    private void checkOnline(PhysicalInstance instance) {
        if (!instance.isOnline()) {
            throw new BusinessException(ErrorCode.INSTANCE_OFFLINE);
        }
        if (!agentCommandService.isAgentConnected(instance.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED);
        }
    }

    private AgentCommandResult dispatchCommand(PhysicalInstance instance, String type, Map<String, Object> payload) {
        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), type, payload, 60000);
        if (result == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "受控端无响应");
        }
        return result;
    }
}
