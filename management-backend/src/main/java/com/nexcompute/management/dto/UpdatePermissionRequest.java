package com.nexcompute.management.dto;

import com.nexcompute.management.domain.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePermissionRequest {
    @NotNull
    private UserRole role;

    @NotBlank
    private String moduleCode;

    private boolean canView;
    private boolean canEdit;
    private boolean canDelete;
}
