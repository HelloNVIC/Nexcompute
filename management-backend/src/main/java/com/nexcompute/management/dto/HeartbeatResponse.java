package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatResponse {
    private Long instanceId;
    private String instanceNumber;
    private String agentToken; // 仅首次注册时返回
    private String status;
    /** 全局受控端管理密码（platform-refinements 11.2：非首次心跳回包补推，离线受控端上线即获取） */
    private String localAdminPassword;
}
