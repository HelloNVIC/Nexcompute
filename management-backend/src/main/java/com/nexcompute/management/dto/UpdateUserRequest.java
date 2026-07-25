package com.nexcompute.management.dto;

import com.nexcompute.management.domain.UserRole;
import lombok.Data;

@Data
public class UpdateUserRequest {
    private String realName;
    private UserRole role;
    private String email;
    private String phone;
    private Long groupId;
    private String status; // ACTIVE / DISABLED
    private String password; // 可选：重置密码
}
