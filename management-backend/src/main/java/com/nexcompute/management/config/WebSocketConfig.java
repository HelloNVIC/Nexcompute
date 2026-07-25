package com.nexcompute.management.config;

import com.nexcompute.management.agent.AgentHandshakeInterceptor;
import com.nexcompute.management.agent.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置（任务 4.3）
 * 受控端命令通道端点 /agent/ws
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/agent/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }

    /** 放大 WS 消息缓冲区（进程列表等大消息回传，默认 8KB 太小） */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024);  // 2MB
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        return container;
    }
}
