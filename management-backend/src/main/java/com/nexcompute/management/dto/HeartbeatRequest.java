package com.nexcompute.management.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 受控端心跳请求（任务 4.2）
 * platform-improvements：增结构化主机 IP 与各容器运行状态顶层字段（保留旧字段兼容）。
 * GPU 结构化信息由受控端置于 status.gpuInfo（name/memoryTotal/memoryUsed/utilization/temperature/driverVersion）。
 */
@Data
public class HeartbeatRequest {

    /** 实例 ID（注册后分配） */
    private Long instanceId;

    /** 物理机编号（首次心跳可能为空，由管理端分配） */
    private String instanceNumber;

    /** 鉴权 token */
    private String agentToken;

    /** 连接模式：direct / tunnel（任务 4.5） */
    private String connectMode;

    /** 机器名 */
    private String machineName;

    /** IP 地址（旧字段，兼容；结构化 IP 列表见 ipAddresses） */
    private String ipAddress;

    /** 主机全部 IP 地址（platform-improvements 任务 4.2） */
    private List<String> ipAddresses;

    /** OS 信息 */
    private String osInfo;

    /** GPU 信息（旧字段，兼容） */
    private String gpuInfo;

    /** 本机各容器运行状态（platform-improvements 任务 4.2） */
    private List<ContainerState> containers;

    /** Agent 版本 */
    private String agentVersion;

    /** 状态快照（CPU/GPU/温度/内存/进程，含结构化 gpuInfo） */
    private Map<String, Object> status;

    /** 存储池根目录（受控端已设置的根目录） */
    private String storageRoot;

    /** MAC（platform-refinements #1：硬件指纹，注册去重用） */
    private String mac;

    /** 机器码（platform-refinements #1：硬件指纹，注册去重用） */
    private String machineCode;

    /** 时间戳 */
    private long timestamp;

    /** 心跳回传的容器运行状态（任务 4.2/4.3） */
    @Data
    public static class ContainerState {
        /** Docker 容器 ID */
        private String id;
        /** 容器名 */
        private String name;
        /** Docker 原生状态（running/exited/created/...） */
        private String state;
    }
}
