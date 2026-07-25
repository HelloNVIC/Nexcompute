package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.dto.HeartbeatRequest;
import com.nexcompute.management.dto.HeartbeatResponse;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.service.HeartbeatService;
import com.nexcompute.management.service.LocalAdminPasswordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 受控端心跳接收端点（任务 4.2）
 * 路径不经过 JWT 认证（在 SecurityConfig 中 permitAll），使用 agentToken 校验。
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class HeartbeatController {

    private final HeartbeatService heartbeatService;
    private final AgentCredentialRepository credentialRepository;
    private final LocalAdminPasswordService localAdminPasswordService;

    @PostMapping("/heartbeat")
    public ApiResponse<HeartbeatResponse> heartbeat(@RequestBody HeartbeatRequest request) {
        // Go 零值（instanceId=0, instanceNumber=""）视为首次注册
        boolean hasInstanceId = request.getInstanceId() != null && request.getInstanceId() > 0;
        boolean hasInstanceNumber = request.getInstanceNumber() != null
                && !request.getInstanceNumber().isBlank();
        boolean isFirstTime = !hasInstanceId && !hasInstanceNumber;
        PhysicalInstance instance = heartbeatService.processHeartbeat(request);

        HeartbeatResponse.HeartbeatResponseBuilder resp = HeartbeatResponse.builder()
                .instanceId(instance.getId())
                .instanceNumber(instance.getInstanceNumber())
                .status(instance.getStatus());

        // 首次注册时回传 token
        if (isFirstTime) {
            credentialRepository.findByInstanceId(instance.getId())
                    .map(AgentCredential::getToken)
                    .ifPresent(resp::agentToken);
        } else {
            // platform-refinements 11.2：非首次心跳回包补推当前全局密码（离线受控端上线即获取）
            resp.localAdminPassword(localAdminPasswordService.getGlobalPassword());
        }

        return ApiResponse.success(resp.build());
    }
}
