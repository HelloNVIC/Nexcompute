package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.domain.MachineAllocation;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.MachineAllocationRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.RegistrationLinkRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.StoragePoolRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 资源分配服务（任务 3.3、3.4、3.5 中的链接管理 + 机器分配）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceAllocationService {

    private final RegistrationLinkRepository linkRepository;
    private final MachineAllocationRepository allocationRepository;
    private final ResearchGroupService groupService;
    private final UserRepository userRepository;
    private final ResearchGroupRepository groupRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final ContainerRepository containerRepository;
    private final StoragePoolRepository poolRepository;
    private final EmailService emailService;

    /**
     * 创建注册链接（任务 3.3）
     * 导师创建，UUID，设置可用次数与过期时间
     */
    @Audited(action = "REGISTRATION_LINK_CREATE", targetType = "REGISTRATION_LINK", targetIdExpr = "#result.token")
    @Transactional
    public RegistrationLink createRegistrationLink(int remainingCount, Instant expireAt) {
        Long mentorId = SecurityUtils.getCurrentUserId();
        var group = groupService.getMyGroup();

        RegistrationLink link = RegistrationLink.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .groupId(group.getId())
                .creatorId(mentorId)
                .remainingCount(remainingCount)
                .expireAt(expireAt)
                .status("ACTIVE")
                .build();
        link = linkRepository.save(link);
        log.info("[RegLink] 导师 {} 创建注册链接: token={}, 次数={}, 过期={}",
                mentorId, link.getToken(), remainingCount, expireAt);
        return link;
    }

    /**
     * 作废注册链接（任务 3.4）
     */
    @Audited(action = "REGISTRATION_LINK_REVOKE", targetType = "REGISTRATION_LINK", targetIdExpr = "#token")
    @Transactional
    public void revokeLink(String token) {
        RegistrationLink link = linkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));
        // 导师只能作废自己创建的链接
        if (SecurityUtils.getCurrentRole() == com.nexcompute.management.domain.UserRole.MENTOR) {
            if (!link.getCreatorId().equals(SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只能作废自己创建的链接");
            }
        }
        link.setStatus("REVOKED");
        linkRepository.save(link);
        log.info("[RegLink] 注册链接已作废: {}", token);
    }

    /** 导师查看自己创建的注册链接 */
    public List<RegistrationLink> myLinks() {
        return linkRepository.findByCreatorIdOrderByCreatedAtDesc(SecurityUtils.getCurrentUserId());
    }

    /**
     * 导师分配机器给学生（任务 3.2 / resource-allocation spec）
     * platform-refinements 8.2：扩展单容器内存上限（perContainerMemoryMb，可空=不限）。
     * upsert：若该学生已在此实例上被分配（如管理员课题组分配），更新内存上限而非冲突。
     */
    @Audited(action = "MACHINE_ALLOCATE", targetType = "MACHINE_ALLOCATION", targetIdExpr = "#result.id")
    @Transactional
    public MachineAllocation allocateMachine(Long instanceId, Long studentId, Long groupId,
                                            Integer perContainerMemoryMb) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        PhysicalInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        boolean[] isNew = {false};
        MachineAllocation allocation = allocationRepository.findByInstanceIdAndUserId(instanceId, studentId)
                .map(existing -> {
                    existing.setGroupId(groupId != null ? groupId : existing.getGroupId());
                    existing.setPerContainerMemoryMb(perContainerMemoryMb);
                    return allocationRepository.save(existing);
                })
                .orElseGet(() -> {
                    isNew[0] = true;
                    return allocationRepository.save(MachineAllocation.builder()
                            .instanceId(instanceId)
                            .userId(studentId)
                            .groupId(groupId)
                            .allocatedBy(SecurityUtils.getCurrentUserId())
                            .perContainerMemoryMb(perContainerMemoryMb)
                            .build());
                });
        // email-notification 5.3：新分配实例成功后异步通知学生（已存在分配的更新不重复发送）
        if (isNew[0]) {
            sendInstanceAllocated(student, instance);
        }
        return allocation;
    }

    /**
     * 管理员按课题组分配物理实例（platform-refinements 8.1）。
     * 为课题组所有成员创建（或更新）使用权，perContainerMemoryMb=null（不限）。
     */
    @Audited(action = "MACHINE_ALLOCATE_GROUP", targetType = "MACHINE_ALLOCATION", targetIdExpr = "#instanceId")
    @Transactional
    public List<MachineAllocation> allocateMachineToGroup(Long instanceId, Long groupId) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可按课题组分配");
        }
        List<User> members = userRepository.findByGroupId(groupId);
        Long adminId = SecurityUtils.getCurrentUserId();
        PhysicalInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        List<User> newlyAllocated = new ArrayList<>();
        List<MachineAllocation> result = new ArrayList<>();
        for (User m : members) {
            MachineAllocation existing = allocationRepository
                    .findByInstanceIdAndUserId(instanceId, m.getId()).orElse(null);
            if (existing != null) {
                existing.setGroupId(groupId);
                result.add(allocationRepository.save(existing));
            } else {
                newlyAllocated.add(m);
                result.add(allocationRepository.save(MachineAllocation.builder()
                        .instanceId(instanceId)
                        .userId(m.getId())
                        .groupId(groupId)
                        .allocatedBy(adminId)
                        .build()));
            }
        }
        // email-notification 5.3：新分配的成员通知（已存在分配的不重复发送）
        for (User m : newlyAllocated) {
            sendInstanceAllocated(m, instance);
        }
        return result;
    }

    /** 撤销分配的影响（platform-refinements #3）：该学生在该实例上的运行容器与存储池 */
    @Transactional(readOnly = true)
    public Map<String, Object> allocationImpact(Long allocationId) {
        MachineAllocation m = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分配记录不存在"));
        String studentName = userRepository.findById(m.getUserId())
                .map(User::getRealName).orElse("-");
        List<Map<String, Object>> running = containerRepository.findByInstanceId(m.getInstanceId()).stream()
                .filter(c -> c.getOwnerId().equals(m.getUserId()) && "RUNNING".equals(c.getStatus()))
                .map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", c.getId());
                    cm.put("name", c.getName());
                    return cm;
                }).toList();
        List<Map<String, Object>> pools = poolRepository.findByInstanceId(m.getInstanceId()).stream()
                .filter(p -> p.getOwnerId().equals(m.getUserId()))
                .map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.getId());
                    pm.put("poolName", p.getPoolName());
                    return pm;
                }).toList();
        return Map.of(
                "studentName", studentName,
                "instanceId", m.getInstanceId(),
                "runningContainers", running,
                "storagePools", pools);
    }

    /** 课题组维度已分配资源列表（platform-refinements #4/#7）。 */
    @Transactional(readOnly = true)
    public List<GroupAllocationDto> listGroupAllocations() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();
        // 导师：限定自己课题组
        Long filterGroupId = null;
        if (role == UserRole.MENTOR) {
            filterGroupId = groupRepository.findByMentorId(userId)
                    .map(ResearchGroup::getId).orElse(null);
            if (filterGroupId == null) return List.of();
        }
        List<MachineAllocation> all = allocationRepository.findGroupAllocations();
        Map<String, GroupAllocationDto> byKey = new LinkedHashMap<>();
        for (MachineAllocation m : all) {
            if (filterGroupId != null && !filterGroupId.equals(m.getGroupId())) continue;
            String key = m.getGroupId() + "-" + m.getInstanceId();
            if (byKey.containsKey(key)) continue;
            String groupName = groupRepository.findById(m.getGroupId())
                    .map(ResearchGroup::getName).orElse("课题组#" + m.getGroupId());
            PhysicalInstance inst = instanceRepository.findById(m.getInstanceId()).orElse(null);
            byKey.put(key, new GroupAllocationDto(
                    m.getGroupId(), groupName,
                    m.getInstanceId(),
                    inst != null ? inst.getInstanceNumber() : null,
                    inst != null ? inst.getMachineName() : null,
                    m.getAllocatedAt()));
        }
        return new ArrayList<>(byKey.values());
    }

    /** 课题组分配视图（platform-refinements #4） */
    public record GroupAllocationDto(Long groupId, String groupName, Long instanceId,
                                      String instanceNumber, String machineName,
                                      Instant allocatedAt) {}

    /** 撤销机器分配 */
    @Audited(action = "MACHINE_DEALLOCATE", targetType = "MACHINE_ALLOCATION", targetIdExpr = "#id")
    @Transactional
    public void deallocateMachine(Long id) {
        MachineAllocation m = allocationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分配记录不存在"));
        allocationRepository.deleteById(id);
        // email-notification 5.4：撤销分配后异步通知被撤销用户
        sendInstanceDeallocated(m.getUserId(), m.getInstanceId());
    }

    /**
     * 撤销课题组在指定实例上的分配（删除该组所有成员在此实例上的分配记录）。
     */
    @Audited(action = "MACHINE_DEALLOCATE_GROUP", targetType = "MACHINE_ALLOCATION", targetIdExpr = "#instanceId")
    @Transactional
    public int deallocateGroup(Long instanceId, Long groupId) {
        List<MachineAllocation> rows = allocationRepository.findByInstanceIdAndGroupId(instanceId, groupId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该课题组在此实例上无分配记录");
        }
        allocationRepository.deleteAll(rows);
        log.info("[Allocation] 撤销课题组 {} 在实例 {} 上的分配（{} 条）", groupId, instanceId, rows.size());
        // email-notification 5.4：撤销分配后异步通知各被撤销用户（去重）
        Set<Long> affectedUserIds = new HashSet<>();
        for (MachineAllocation m : rows) affectedUserIds.add(m.getUserId());
        for (Long uid : affectedUserIds) {
            sendInstanceDeallocated(uid, instanceId);
        }
        return rows.size();
    }

    /**
     * 撤销课题组分配的影响：该组所有成员在此实例上的运行容器与存储池（撤销前提示用）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> groupAllocationImpact(Long instanceId, Long groupId) {
        String groupName = groupRepository.findById(groupId)
                .map(ResearchGroup::getName).orElse("课题组#" + groupId);
        List<MachineAllocation> rows = allocationRepository.findByInstanceIdAndGroupId(instanceId, groupId);
        Set<Long> memberIds = new HashSet<>();
        for (MachineAllocation m : rows) memberIds.add(m.getUserId());

        List<Map<String, Object>> running = containerRepository.findByInstanceId(instanceId).stream()
                .filter(c -> memberIds.contains(c.getOwnerId()) && "RUNNING".equals(c.getStatus()))
                .map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", c.getId());
                    cm.put("name", c.getName());
                    cm.put("ownerId", c.getOwnerId());
                    return cm;
                }).toList();
        List<Map<String, Object>> pools = poolRepository.findByInstanceId(instanceId).stream()
                .filter(p -> memberIds.contains(p.getOwnerId()))
                .map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.getId());
                    pm.put("poolName", p.getPoolName());
                    pm.put("ownerId", p.getOwnerId());
                    return pm;
                }).toList();
        return Map.of(
                "groupName", groupName,
                "instanceId", instanceId,
                "memberCount", memberIds.size(),
                "runningContainers", running,
                "storagePools", pools);
    }

    // ===== email-notification 5.3/5.4：实例分配/撤销邮件 =====

    /** 实例分配邮件：instanceName 取 machineName（空则用编号），instanceNumber 为物理机编号。 */
    private void sendInstanceAllocated(User student, PhysicalInstance instance) {
        if (student == null || student.getEmail() == null || student.getEmail().isBlank()) return;
        Map<String, Object> ctx = baseInstanceCtx(instance);
        emailService.sendAt(EmailTrigger.INSTANCE_ALLOCATED, student, ctx);
    }

    /** 实例撤销分配邮件：按 userId 加载收件人。 */
    private void sendInstanceDeallocated(Long userId, Long instanceId) {
        if (userId == null) return;
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        PhysicalInstance instance = instanceRepository.findById(instanceId).orElse(null);
        Map<String, Object> ctx = baseInstanceCtx(instance);
        emailService.sendAt(EmailTrigger.INSTANCE_DEALLOCATED, user, ctx);
    }

    private Map<String, Object> baseInstanceCtx(PhysicalInstance instance) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("operatorName", emailService.resolveOperatorName());
        ctx.put("time", Instant.now());
        if (instance != null) {
            String instanceName = (instance.getMachineName() != null && !instance.getMachineName().isBlank())
                    ? instance.getMachineName() : instance.getInstanceNumber();
            ctx.put("instanceName", instanceName);
            ctx.put("instanceNumber", instance.getInstanceNumber());
        }
        return ctx;
    }

    /** 学生查看分配给自己的机器 */
    public List<MachineAllocation> myAllocatedMachines() {
        Long userId = SecurityUtils.getCurrentUserId();
        UserRole role = SecurityUtils.getCurrentRole();
        List<MachineAllocation> result;
        if (role == UserRole.MENTOR) {
            // platform-refinements #2：导师"已分配机器"显示本课题组的全部分配（不含分配给导师自己的）
            var group = groupRepository.findByMentorId(userId).orElse(null);
            result = group != null
                    ? allocationRepository.findByGroupId(group.getId()).stream()
                        .filter(m -> !m.getUserId().equals(userId))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                    : new ArrayList<>();
        } else {
            result = new ArrayList<>(allocationRepository.findByUserId(userId));
        }
        enrichStudentNames(result);
        return result;
    }

    /** 填充 studentName（platform-refinements #2） */
    private void enrichStudentNames(List<MachineAllocation> list) {
        if (list.isEmpty()) return;
        Set<Long> userIds = new HashSet<>();
        for (MachineAllocation m : list) userIds.add(m.getUserId());
        Map<Long, String> nameMap = userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getRealName));
        for (MachineAllocation m : list) m.setStudentName(nameMap.getOrDefault(m.getUserId(), "-"));
    }

    /** 导师查看分配给课题组学生的机器 */
    public List<MachineAllocation> groupAllocations(Long groupId) {
        return allocationRepository.findByGroupId(groupId);
    }
}
