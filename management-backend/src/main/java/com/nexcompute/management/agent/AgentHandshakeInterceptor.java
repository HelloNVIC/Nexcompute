package com.nexcompute.management.agent;

import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 受控端 WS 握手拦截器（任务 4.1、4.3）
 * 校验受控端身份（token + instanceNumber），验证通过后允许连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    private final PhysicalInstanceRepository instanceRepository;
    private final AgentCredentialRepository credentialRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractHeader(request, "X-Agent-Token");
        String instanceNumber = extractHeader(request, "X-Instance-Number");
        if (token == null || instanceNumber == null) {
            // 尝试从 query 提取
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        if ("token".equals(kv[0])) token = kv[1];
                        if ("instanceNumber".equals(kv[0])) instanceNumber = kv[1];
                    }
                }
            }
        }

        if (token == null || instanceNumber == null) {
            log.warn("[AgentWS] 握手失败：缺少 token 或 instanceNumber");
            return false;
        }

        // 校验凭证
        PhysicalInstance instance = instanceRepository.findByInstanceNumber(instanceNumber).orElse(null);
        if (instance == null) {
            log.warn("[AgentWS] 握手失败：实例不存在 {}", instanceNumber);
            return false;
        }
        AgentCredential credential = credentialRepository.findByInstanceId(instance.getId()).orElse(null);
        if (credential == null || credential.getRevoked() || !token.equals(credential.getToken())) {
            log.warn("[AgentWS] 握手失败：凭证无效 {}", instanceNumber);
            return false;
        }

        attributes.put("instanceNumber", instanceNumber);
        attributes.put("instanceId", instance.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String extractHeader(ServerHttpRequest request, String headerName) {
        return request.getHeaders().getFirst(headerName);
    }
}
