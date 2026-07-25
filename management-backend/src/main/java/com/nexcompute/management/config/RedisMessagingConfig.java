package com.nexcompute.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import java.util.function.Consumer;

/**
 * Redis 消息队列基础（任务 1.5）
 * 发布/订阅模式，用于系统内部事件广播（如 SSE 推送触发、通知分发）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisMessagingConfig {

    public static final String CHANNEL_MONITORING = "nexcompute:monitoring";
    public static final String CHANNEL_NOTIFICATION = "nexcompute:notification";
    public static final String CHANNEL_AGENT_EVENT = "nexcompute:agent-event";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 发布消息到指定频道
     */
    public void publish(String channel, Object payload) {
        redisTemplate.convertAndSend(channel, payload);
    }

    /**
     * 简单消息监听器：将 Redis 消息反序列化后交给消费者
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            MessageListenerAdapter notificationListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisTemplate.getConnectionFactory());
        container.addMessageListener(notificationListenerAdapter,
                new ChannelTopic(CHANNEL_NOTIFICATION));
        return container;
    }

    /**
     * 默认通知监听适配器
     */
    @Bean
    public MessageListenerAdapter notificationListenerAdapter(NotificationSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }

    /**
     * 通知订阅者占位：SSE 推送层（任务 11.4 / 13.8）会注入真正的消费者
     */
    @Slf4j
    public static class NotificationSubscriber implements MessageListener {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private Consumer<String> handler;

        public void setHandler(Consumer<String> handler) {
            this.handler = handler;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            String body = new String(message.getBody());
            log.debug("[Redis-Sub] 收到通知: {}", body);
            if (handler != null) {
                handler.accept(body);
            }
        }
    }

    @Bean
    public NotificationSubscriber notificationSubscriber() {
        return new NotificationSubscriber();
    }
}
