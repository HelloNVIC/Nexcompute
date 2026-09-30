package com.nexcompute.management.agent;

import lombok.Data;

/**
 * 受控端回传的进度消息（type=="progress"）。
 * channel 识别后只更新进度，不 complete future（R1）。
 * registry-image-distribution D5：新增 text（分层文本，image.pull 用，如 "a1b2c3: Downloading 45%"）；OTA 不使用，可缺省。
 */
@Data
public class ProgressMessage {
    private String type;       // "progress"
    private String commandId;
    private String stage;      // downloading/verifying/backing_up/replacing/waiting/pulling
    private Integer percent;   // 0-100
    private String text;       // 分层状态文本（可空，image.pull 新增）
    private Long timestamp;
}
