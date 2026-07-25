package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 用户信息必填项配置（platform-refinements #5）。单行（id=1）。
 * username 与 role 始终必填，不在配置内。
 */
@Entity
@Table(name = "user_field_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFieldConfig {

    @Id
    @Column(name = "id")
    private Short id; // 恒为 1

    @Column(name = "real_name", nullable = false)
    private Boolean realName;

    @Column(name = "student_id", nullable = false)
    private Boolean studentId;

    @Column(name = "email", nullable = false)
    private Boolean email;

    @Column(name = "phone", nullable = false)
    private Boolean phone;

    @Column(name = "group_id", nullable = false)
    private Boolean groupId;
}
