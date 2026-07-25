package com.nexcompute.management.agent;

import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 受控端命令派发服务（任务 4.3、6.3、10.3 等）
 * 各业务模块通过此服务向受控端派发命令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCommandService {

    private final AgentCommandChannel channel;
    private final AgentCredentialRepository credentialRepository;
    private final PhysicalInstanceRepository instanceRepository;

    /**
     * 派发命令并同步等待结果（默认 30 秒超时）
     */
    public AgentCommandResult sendCommand(String instanceNumber, String type, Map<String, Object> payload) {
        return sendCommand(instanceNumber, type, payload, 30000);
    }

    public AgentCommandResult sendCommand(String instanceNumber, String type, Map<String, Object> payload, long timeoutMs) {
        AgentCommand command = AgentCommand.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .token(resolveToken(instanceNumber))
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
        log.info("[AgentCmd] 派发命令: instance={} type={} id={}", instanceNumber, type, command.getId());
        return channel.dispatchAndWait(instanceNumber, command, timeoutMs);
    }

    /**
     * 异步派发命令（不等待结果）
     */
    public boolean fireAndForget(String instanceNumber, String type, Map<String, Object> payload) {
        AgentCommand command = AgentCommand.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .token(resolveToken(instanceNumber))
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
        return channel.dispatch(instanceNumber, command);
    }

    public boolean isAgentConnected(String instanceNumber) {
        return channel.isAgentConnected(instanceNumber);
    }

    /**
     * 广播命令至所有已连接的受控端（platform-refinements 11.1：全局密码下发）。
     * 离线受控端由心跳回包补推（HeartbeatController）。
     *
     * @return 已发送的受控端数量
     */
    public int broadcast(String type, Map<String, Object> payload) {
        int sent = 0;
        for (String instanceNumber : channel.connectedInstanceNumbers()) {
            if (fireAndForget(instanceNumber, type, payload)) {
                sent++;
            }
        }
        return sent;
    }

    /** 取出该实例的 agentToken 用于命令鉴权（任务 4.8） */
    private String resolveToken(String instanceNumber) {
        try {
            PhysicalInstance instance = instanceRepository.findByInstanceNumber(instanceNumber).orElse(null);
            if (instance == null) return "";
            return credentialRepository.findByInstanceId(instance.getId())
                    .filter(c -> !Boolean.TRUE.equals(c.getRevoked()))
                    .map(AgentCredential::getToken)
                    .orElse("");
        } catch (Exception e) {
            log.warn("[AgentCmd] 取凭证失败: {}", e.getMessage());
            return "";
        }
    }
}
