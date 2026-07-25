package com.nexcompute.management.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 管理端下发至受控端的命令（任务 4.3）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommand {

    /** 命令唯一 ID（用于关联结果） */
    private String id;

    /** 命令类型（system.restart / container.create / ...） */
    private String type;

    /** 鉴权令牌（受控端校验命令来源，任务 4.8） */
    private String token;

    /** 命令参数 */
    private Map<String, Object> payload;

    /** 时间戳 */
    private long timestamp;
}
