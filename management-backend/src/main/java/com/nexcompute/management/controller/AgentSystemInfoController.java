package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.SystemInfo;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.SystemInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受控端"关于"信息接口。
 * 置于 /agent/**（SecurityConfig permitAll + WebConfig 排除），service 内校验 agent token/instanceNumber。
 * 返回管理端版本与系统信息（维护人等），供受控端"关于"按钮展示。
 */
@RestController
@RequestMapping("/agent/about")
@RequiredArgsConstructor
public class AgentSystemInfoController {

    private final PhysicalInstanceRepository instanceRepository;
    private final AgentCredentialRepository credentialRepository;
    private final SystemInfoRepository systemInfoRepository;

    /** 管理端版本（来自 application.yml: nexcompute.management.version，默认 0.0.1-SNAPSHOT） */
    @Value("${nexcompute.management.version:0.0.1-SNAPSHOT}")
    private String managementVersion;

    @GetMapping
    public ApiResponse<Map<String, Object>> about(
            @RequestHeader("X-Agent-Token") String token,
            @RequestHeader("X-Instance-Number") String instanceNumber) {
        validateAgent(token, instanceNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managementVersion", managementVersion);

        SystemInfo info = systemInfoRepository.findSingleton()
                .orElseGet(() -> SystemInfo.builder().id((short) 1).build());
        result.put("maintainer", nullSafe(info.getMaintainer()));
        result.put("maintainerPhone", nullSafe(info.getMaintainerPhone()));
        result.put("owner", nullSafe(info.getOwner()));
        result.put("ownerPhone", nullSafe(info.getOwnerPhone()));
        return ApiResponse.success(result);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 校验 agent token + instanceNumber（与 WS 握手一致的凭证校验） */
    private PhysicalInstance validateAgent(String token, String instanceNumber) {
        if (token == null || token.isBlank() || instanceNumber == null || instanceNumber.isBlank()) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "缺少 agent 凭证");
        }
        PhysicalInstance instance = instanceRepository.findByInstanceNumber(instanceNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        AgentCredential credential = credentialRepository.findByInstanceId(instance.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "凭证不存在"));
        if (credential.getRevoked() || !token.equals(credential.getToken())) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "凭证无效");
        }
        return instance;
    }
}
