package com.nexcompute.management.dto;

import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDto {
    private Long id;
    private String username;
    private String realName;
    private UserRole role;
    private String studentId;
    private String email;
    private String phone;
    private Long groupId;
    private String groupName;
    private String status;
    /** 注册时间（platform-refinements #5） */
    private Instant createdAt;

    public static UserInfoDto from(User u, String groupName) {
        return UserInfoDto.builder()
                .id(u.getId())
                .username(u.getUsername())
                .realName(u.getRealName())
                .role(u.getRole())
                .studentId(u.getStudentId())
                .email(u.getEmail())
                .phone(u.getPhone())
                .groupId(u.getGroupId())
                .groupName(groupName)
                .status(u.getStatus())
                .createdAt(u.getCreatedAt())
                .build();
    }
}
