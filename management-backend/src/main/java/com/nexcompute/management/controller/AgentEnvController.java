package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.dto.AgentEnvFileDto;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.service.EnvFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 受控端环境文件清单接口（D4）。
 * 置于 /agent/**（SecurityConfig permitAll + WebConfig 排除），service 内校验 agent token/instanceNumber。
 * 受控端按 MD5 增量同步时拉取此清单。
 */
@Slf4j
@RestController
@RequestMapping("/agent/env")
@RequiredArgsConstructor
public class AgentEnvController {

    private final EnvFileService envFileService;
    private final PhysicalInstanceRepository instanceRepository;
    private final AgentCredentialRepository credentialRepository;

    /** 环境文件清单（含下载用 sourcePath） */
    @GetMapping("/list")
    public ApiResponse<List<AgentEnvFileDto>> list(
            @RequestHeader("X-Agent-Token") String token,
            @RequestHeader("X-Instance-Number") String instanceNumber) {
        validateAgent(token, instanceNumber);
        return ApiResponse.success(envFileService.agentList());
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
