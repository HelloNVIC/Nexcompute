package com.nexcompute.management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 受控端 WebSocket 会话注册表（任务 4.3）
 * 维护 物理机编号 -> WebSocketSession 映射，管理命令派发与结果接收。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionRegistry {

    private final ObjectMapper objectMapper;

    /** instanceNumber -> WebSocketSession */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** commandId -> 等待结果的 latch + result */
    private final Map<String, ResultLatch> pendingResults = new ConcurrentHashMap<>();

    public void register(String instanceNumber, WebSocketSession session) {
        WebSocketSession old = sessions.put(instanceNumber, session);
        if (old != null && old.isOpen()) {
            // platform-refinements：同一实例重复连入（多为受控端多进程/重复启动），关闭旧会话
            log.warn("[AgentWS] 实例 {} 已有连接，关闭旧会话（可能存在重复受控端进程）", instanceNumber);
            try {
                old.close();
            } catch (IOException e) {
                log.warn("[AgentWS] 关闭旧会话失败: {}", e.getMessage());
            }
        }
        log.info("[AgentWS] 受控端已连接: {}", instanceNumber);
    }

    public void unregister(String instanceNumber, WebSocketSession session) {
        sessions.remove(instanceNumber, session);
        log.info("[AgentWS] 受控端已断开: {}", instanceNumber);
    }

    public boolean isConnected(String instanceNumber) {
        WebSocketSession session = sessions.get(instanceNumber);
        return session != null && session.isOpen();
    }

    /** 所有已连接的物理机编号（platform-refinements 11.1：全局密码广播） */
    public java.util.Set<String> connectedInstanceNumbers() {
        java.util.Set<String> result = new java.util.HashSet<>();
        sessions.forEach((k, v) -> {
            if (v != null && v.isOpen()) result.add(k);
        });
        return result;
    }

    public WebSocketSession getSession(String instanceNumber) {
        return sessions.get(instanceNumber);
    }

    public void registerPending(String commandId) {
        pendingResults.put(commandId, new ResultLatch());
    }

    public boolean awaitResult(String commandId, AgentCommandResult result, long timeoutMs) {
        ResultLatch latch = pendingResults.get(commandId);
        if (latch == null) return false;
        latch.result = result;
        latch.countDownLatch.countDown();
        return true;
    }

    public AgentCommandResult waitForResult(String commandId, long timeoutMs) {
        ResultLatch latch = pendingResults.get(commandId);
        if (latch == null) return null;
        try {
            if (!latch.countDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null;
            }
            return latch.result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pendingResults.remove(commandId);
        }
    }

    public void sendCommand(String instanceNumber, AgentCommand command) throws IOException {
        WebSocketSession session = sessions.get(instanceNumber);
        if (session == null || !session.isOpen()) {
            throw new IOException("受控端未连接: " + instanceNumber);
        }
        String json = objectMapper.writeValueAsString(command);
        session.sendMessage(new TextMessage(json));
    }

    private static class ResultLatch {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        volatile AgentCommandResult result;
    }
}
