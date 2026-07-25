package com.nexcompute.management.agent;

/**
 * 命令派发通道（任务 4.3）
 * 向指定物理实例的受控端派发命令，并异步等待结果。
 */
public interface AgentCommandChannel {

    /**
     * 向指定实例派发命令（异步）
     *
     * @param instanceNumber 物理机编号
     * @param command        命令
     * @return true 表示已发送（受控端在线）；false 表示受控端离线
     */
    boolean dispatch(String instanceNumber, AgentCommand command);

    /**
     * 派发命令并同步等待结果
     *
     * @param instanceNumber 物理机编号
     * @param command        命令
     * @param timeoutMs      超时毫秒
     * @return 执行结果，受控端离线或超时返回 null
     */
    AgentCommandResult dispatchAndWait(String instanceNumber, AgentCommand command, long timeoutMs);

    /**
     * 判断受控端是否在线（WS 已连接）
     */
    boolean isAgentConnected(String instanceNumber);

    /**
     * 所有已连接的物理机编号（platform-refinements 11.1：全局广播）
     */
    java.util.Set<String> connectedInstanceNumbers();
}
