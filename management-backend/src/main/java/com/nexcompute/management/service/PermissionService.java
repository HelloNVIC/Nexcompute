package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PermissionMatrix;
import com.nexcompute.management.domain.PermissionModule;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.PermissionMatrixDto;
import com.nexcompute.management.repository.PermissionMatrixRepository;
import com.nexcompute.management.repository.PermissionModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限服务（任务 2.3、2.4）
 * 权限矩阵 CRUD 与权限校验。权限矩阵缓存于 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionMatrixRepository matrixRepository;
    private final PermissionModuleRepository moduleRepository;

    /**
     * 校验当前角色对模块是否有操作权限（任务 2.4）
     */
    @Cacheable(value = "permission", key = "#role + ':' + #moduleCode + ':' + #action")
    public boolean hasPermission(UserRole role, String moduleCode, String action) {
        PermissionModule module = moduleRepository.findByCode(moduleCode).orElse(null);
        if (module == null) {
            return false;
        }
        PermissionMatrix matrix = matrixRepository.findByRoleAndModuleId(role, module.getId())
                .orElse(null);
        if (matrix == null) {
            return false;
        }
        return switch (action) {
            case "view" -> matrix.getCanView();
            case "edit" -> matrix.getCanEdit();
            case "delete" -> matrix.getCanDelete();
            default -> false;
        };
    }

    /** 单模块三操作权限（角色视角） */
    public record RolePerm(boolean canView, boolean canEdit, boolean canDelete) {}

    /**
     * 某角色的全模块权限映射（moduleCode -> 三操作），供前端按权限矩阵渲染菜单与按钮。
     * 缺行（未配置）视为全 false，与 hasPermission 判定一致。
     */
    @Cacheable(value = "permission", key = "'my:' + #role")
    public Map<String, RolePerm> getPermissionsForRole(UserRole role) {
        List<PermissionModule> modules = moduleRepository.findAll();
        Map<Long, PermissionMatrix> byModule = matrixRepository.findAll().stream()
                .filter(m -> m.getRole() == role)
                .collect(Collectors.toMap(PermissionMatrix::getModuleId, m -> m));
        return modules.stream().collect(Collectors.toMap(
                PermissionModule::getCode,
                m -> {
                    PermissionMatrix pm = byModule.get(m.getId());
                    return new RolePerm(
                            pm != null && pm.getCanView(),
                            pm != null && pm.getCanEdit(),
                            pm != null && pm.getCanDelete());
                }));
    }

    /**
     * 获取完整权限矩阵（任务 2.3）
     */
    public List<PermissionMatrixDto> getMatrix() {
        List<PermissionModule> modules = moduleRepository.findAll();
        List<PermissionMatrix> matrices = matrixRepository.findAll();
        Map<String, Map<Long, PermissionMatrix>> map = matrices.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getRole().name(),
                        Collectors.toMap(PermissionMatrix::getModuleId, m -> m)));

        return modules.stream().map(module -> {
            PermissionMatrixDto dto = new PermissionMatrixDto();
            dto.setModuleCode(module.getCode());
            dto.setModuleName(module.getName());
            dto.setPermissions(Map.of(
                    "ADMIN", toPerm(map.getOrDefault("ADMIN", Map.of()).get(module.getId())),
                    "MENTOR", toPerm(map.getOrDefault("MENTOR", Map.of()).get(module.getId())),
                    "STUDENT", toPerm(map.getOrDefault("STUDENT", Map.of()).get(module.getId()))));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 更新权限矩阵（任务 2.3；D13：记审计）
     */
    @Audited(action = "PERMISSION_UPDATE", targetType = "PERMISSION")
    @Transactional
    @CacheEvict(value = "permission", allEntries = true)
    public void updateMatrix(UserRole role, String moduleCode,
                             boolean canView, boolean canEdit, boolean canDelete) {
        PermissionModule module = moduleRepository.findByCode(moduleCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMISSION_MATRIX_NOT_FOUND));
        PermissionMatrix matrix = matrixRepository.findByRoleAndModuleId(role, module.getId())
                .orElse(PermissionMatrix.builder()
                        .role(role)
                        .moduleId(module.getId())
                        .build());
        matrix.setCanView(canView);
        matrix.setCanEdit(canEdit);
        matrix.setCanDelete(canDelete);
        matrixRepository.save(matrix);
        log.info("[Permission] 权限矩阵已更新: {}:{} view={} edit={} delete={}",
                role, moduleCode, canView, canEdit, canDelete);
    }

    // ===== D13：恢复默认权限矩阵（与 V2__access_control.sql 种子一致） =====

    private static final Set<String> STUDENT_VIEW = Set.of(
            "physical-instance", "container", "image", "storage-pool", "ticket",
            "group", "announcement", "monitoring", "notification");
    private static final Set<String> STUDENT_EDIT_DELETE = Set.of(
            "container", "image", "storage-pool", "ticket");
    private static final Set<String> MENTOR_VIEW = Set.of(
            "physical-instance", "container", "image", "storage-pool", "ticket",
            "group", "announcement", "monitoring", "notification");
    private static final Set<String> MENTOR_EDIT = Set.of(
            "container", "image", "storage-pool", "ticket", "group");
    private static final Set<String> MENTOR_DELETE = Set.of(
            "container", "image", "storage-pool", "ticket");

    /**
     * 恢复平台默认权限矩阵（D13）：按 V2 种子 upsert（既有行原地更新，避免 deleteAll+insert
     * 触发唯一约束 permission_matrix(role, module_id) 冲突），记审计。
     */
    @Audited(action = "PERMISSION_RESET_DEFAULT", targetType = "PERMISSION")
    @Transactional
    @CacheEvict(value = "permission", allEntries = true)
    public void resetToDefault() {
        List<PermissionModule> modules = moduleRepository.findAll();
        for (PermissionModule m : modules) {
            upsert(UserRole.ADMIN, m.getId(), true, true, true);
            upsert(UserRole.STUDENT, m.getId(),
                    STUDENT_VIEW.contains(m.getCode()),
                    STUDENT_EDIT_DELETE.contains(m.getCode()),
                    STUDENT_EDIT_DELETE.contains(m.getCode()));
            upsert(UserRole.MENTOR, m.getId(),
                    MENTOR_VIEW.contains(m.getCode()),
                    MENTOR_EDIT.contains(m.getCode()),
                    MENTOR_DELETE.contains(m.getCode()));
        }
        // 删除多余的模块行（如历史上模块已删除但遗留矩阵记录），保持与当前模块集一致
        Set<Long> moduleIds = modules.stream().map(PermissionModule::getId).collect(Collectors.toSet());
        matrixRepository.findAll().stream()
                .filter(pm -> !moduleIds.contains(pm.getModuleId()))
                .forEach(matrixRepository::delete);
        log.info("[Permission] 权限矩阵已恢复为平台默认配置");
    }

    /** upsert：存在则更新，不存在则插入（避免唯一约束冲突） */
    private void upsert(UserRole role, Long moduleId, boolean canView, boolean canEdit, boolean canDelete) {
        PermissionMatrix m = matrixRepository.findByRoleAndModuleId(role, moduleId)
                .orElseGet(() -> PermissionMatrix.builder().role(role).moduleId(moduleId).build());
        m.setCanView(canView);
        m.setCanEdit(canEdit);
        m.setCanDelete(canDelete);
        matrixRepository.save(m);
    }

    private PermissionMatrixDto.Perm toPerm(PermissionMatrix m) {
        if (m == null) {
            return new PermissionMatrixDto.Perm(false, false, false);
        }
        return new PermissionMatrixDto.Perm(m.getCanView(), m.getCanEdit(), m.getCanDelete());
    }
}
