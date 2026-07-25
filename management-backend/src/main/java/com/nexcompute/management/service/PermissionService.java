package com.nexcompute.management.service;

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
     * 更新权限矩阵（任务 2.3）
     */
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

    private PermissionMatrixDto.Perm toPerm(PermissionMatrix m) {
        if (m == null) {
            return new PermissionMatrixDto.Perm(false, false, false);
        }
        return new PermissionMatrixDto.Perm(m.getCanView(), m.getCanEdit(), m.getCanDelete());
    }
}
