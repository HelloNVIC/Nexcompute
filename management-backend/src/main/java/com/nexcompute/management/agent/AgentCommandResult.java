package com.nexcompute.management.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 受控端回传的命令执行结果（任务 4.3）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommandResult {

    private String commandId;
    private boolean success;
    private String output;
    private String error;
    private long timestamp;
}
