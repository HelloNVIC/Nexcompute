package com.nexcompute.management.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
public class PermissionMatrixDto {
    private String moduleCode;
    private String moduleName;
    private Map<String, Perm> permissions; // role -> perm

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Perm {
        private boolean canView;
        private boolean canEdit;
        private boolean canDelete;
    }
}
