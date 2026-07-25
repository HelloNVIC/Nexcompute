package com.nexcompute.management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 受控端 WebSocket 命令通道服务端处理器（任务 4.3）
 * 处理受控端连入、命令结果接收。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentSessionRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String instanceNumber = extractInstanceNumber(session);
        if (instanceNumber == null) {
            log.warn("[AgentWS] 连接缺少实例编号，关闭");
            closeQuietly(session);
            return;
        }
        registry.register(instanceNumber, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            AgentCommandResult result = objectMapper.readValue(message.getPayload(), AgentCommandResult.class);
            String instanceNumber = extractInstanceNumber(session);
            log.debug("[AgentWS] 收到结果: instance={} cmd={}", instanceNumber, result.getCommandId());
            registry.awaitResult(result.getCommandId(), result, 0);
        } catch (Exception e) {
            log.warn("[AgentWS] 解析结果失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String instanceNumber = extractInstanceNumber(session);
        if (instanceNumber != null) {
            registry.unregister(instanceNumber, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[AgentWS] 传输错误: {}", exception.getMessage());
    }

    private String extractInstanceNumber(WebSocketSession session) {
        // 从握手 attributes 或 query 参数提取
        Map<String, Object> attrs = session.getAttributes();
        Object num = attrs.get("instanceNumber");
        if (num != null) return num.toString();
        // 从 URI query 提取
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && "instanceNumber".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
        }
    }
}
