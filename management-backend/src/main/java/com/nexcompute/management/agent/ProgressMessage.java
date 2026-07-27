package com.nexcompute.management.agent;

import lombok.Data;

/**
 * 受控端回传的升级进度消息（type=="progress"）。
 * channel 识别后只更新 task 进度，不 complete future（R1）。
 */
@Data
public class ProgressMessage {
    private String type;       // "progress"
    private String commandId;
    private String stage;      // downloading/verifying/backing_up/replacing/waiting
    private Integer percent;   // 0-100
    private Long timestamp;
}
