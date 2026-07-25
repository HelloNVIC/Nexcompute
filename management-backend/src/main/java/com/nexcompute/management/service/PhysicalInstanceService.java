package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.MachineAllocationRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
