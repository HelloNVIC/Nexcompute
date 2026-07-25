package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 受控端镜像接口（platform-refinements）。
 * 置于 /agent/**（SecurityConfig permitAll + WebConfig 排除），service 内校验 agent token/instanceNumber。
 * 修复受控端公共镜像同步 EOF：原 /images/public/list 仅受 JWT 保护，受控端以 agent token 请求被拦截、
 * 响应体为空致 json.Decode 返回 EOF。
 */
@Slf4j
@RestController
@RequestMapping("/agent/images")
@RequiredArgsConstructor
public class AgentImageController {

    private final ImageService imageService;
    private final PhysicalInstanceRepository instanceRepository;
    private final AgentCredentialRepository credentialRepository;

    /** 公共镜像列表（受控端同步用，复用 imageService.listPublicImages()） */
    @GetMapping("/public/list")
    public ApiResponse<List<ImageMetadata>> publicList(
            @RequestHeader("X-Agent-Token") String token,
            @RequestHeader("X-Instance-Number") String instanceNumber) {
        validateAgent(token, instanceNumber);
        return ApiResponse.success(imageService.listPublicImages());
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
