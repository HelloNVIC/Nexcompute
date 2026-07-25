package com.nexcompute.management.service;

import com.nexcompute.management.domain.PermissionMatrix;
import com.nexcompute.management.domain.PermissionModule;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.PermissionMatrixRepository;
import com.nexcompute.management.repository.PermissionModuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PermissionService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private PermissionMatrixRepository matrixRepository;
    @Mock
    private PermissionModuleRepository moduleRepository;

    @InjectMocks
    private PermissionService permissionService;

    @Test
    void hasPermission_granted_returnsTrue() {
        PermissionModule module = PermissionModule.builder().id(1L).code("container").name("容器").build();
        PermissionMatrix matrix = PermissionMatrix.builder()
                .role(UserRole.STUDENT).moduleId(1L)
                .canView(true).canEdit(true).canDelete(false)
                .build();

        when(moduleRepository.findByCode("container")).thenReturn(Optional.of(module));
        when(matrixRepository.findByRoleAndModuleId(UserRole.STUDENT, 1L)).thenReturn(Optional.of(matrix));

        assertThat(permissionService.hasPermission(UserRole.STUDENT, "container", "view")).isTrue();
        assertThat(permissionService.hasPermission(UserRole.STUDENT, "container", "edit")).isTrue();
        assertThat(permissionService.hasPermission(UserRole.STUDENT, "container", "delete")).isFalse();
    }

    @Test
    void hasPermission_moduleNotFound_returnsFalse() {
        when(moduleRepository.findByCode("nonexistent")).thenReturn(Optional.empty());

        assertThat(permissionService.hasPermission(UserRole.ADMIN, "nonexistent", "view")).isFalse();
    }

    @Test
    void hasPermission_noMatrix_returnsFalse() {
        PermissionModule module = PermissionModule.builder().id(1L).code("container").build();
        when(moduleRepository.findByCode("container")).thenReturn(Optional.of(module));
        when(matrixRepository.findByRoleAndModuleId(UserRole.MENTOR, 1L)).thenReturn(Optional.empty());

        assertThat(permissionService.hasPermission(UserRole.MENTOR, "container", "view")).isFalse();
    }
}
