package com.nexcompute.management.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 命令通道实现（任务 4.3）
 * 通过 WebSocket 向受控端派发命令，支持同步等待结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCommandChannelImpl implements AgentCommandChannel {

    private final AgentSessionRegistry registry;

    @Override
    public boolean dispatch(String instanceNumber, AgentCommand command) {
        if (!registry.isConnected(instanceNumber)) {
            log.warn("[AgentCmd] 受控端离线，无法派发: {}", instanceNumber);
            return false;
        }
        try {
            registry.sendCommand(instanceNumber, command);
            return true;
        } catch (IOException e) {
            log.error("[AgentCmd] 派发命令失败: {} - {}", instanceNumber, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentCommandResult dispatchAndWait(String instanceNumber, AgentCommand command, long timeoutMs) {
        if (command.getId() == null) {
            command.setId(UUID.randomUUID().toString());
        }
        command.setTimestamp(System.currentTimeMillis());

        registry.registerPending(command.getId());
        try {
            if (!dispatch(instanceNumber, command)) {
                registry.waitForResult(command.getId(), 0); // 清理 pending
                return null;
            }
            return registry.waitForResult(command.getId(), timeoutMs);
        } catch (Exception e) {
            log.error("[AgentCmd] dispatchAndWait 异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAgentConnected(String instanceNumber) {
        return registry.isConnected(instanceNumber);
    }

    @Override
    public java.util.Set<String> connectedInstanceNumbers() {
        return registry.connectedInstanceNumbers();
    }
}
