package com.nexcompute.management.common;

import lombok.Getter;

/**
 * 错误码定义
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 认证相关 1xxx
    LOGIN_FAILED(1001, "用户名或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已禁用"),
    TOKEN_INVALID(1003, "令牌无效或已过期"),
    PASSWORD_TOO_WEAK(1004, "密码强度不足"),

    // 权限相关 2xxx
    PERMISSION_DENIED(2001, "权限不足"),
    PERMISSION_MATRIX_NOT_FOUND(2002, "权限矩阵配置不存在"),

    // 用户/课题组相关 3xxx
    USER_NOT_FOUND(3001, "用户不存在"),
    USER_ALREADY_EXISTS(3002, "用户已存在"),
    GROUP_NOT_FOUND(3003, "课题组不存在"),
    REGISTRATION_LINK_INVALID(3004, "注册链接无效或已失效"),
    REGISTRATION_LINK_EXPIRED(3005, "注册链接已过期"),
    MACHINE_NUMBER_CONFLICT(3006, "物理机编号冲突"),

    // 物理实例相关 4xxx
    INSTANCE_NOT_FOUND(4001, "物理实例不存在"),
    INSTANCE_OFFLINE(4002, "物理实例离线"),
    AGENT_NOT_CONNECTED(4003, "受控端未连接"),

    // 容器相关 5xxx
    CONTAINER_NOT_FOUND(5001, "容器不存在"),
    PORT_ALLOCATION_FAILED(5002, "端口分配失败"),
    CONTAINER_COMMAND_FAILED(5003, "容器命令执行失败"),
    STORAGE_POOL_IN_USE(5004, "存储池有运行容器依赖"),
    QUOTA_EXCEEDED(5005, "资源限制超出配额"),

    // 存储池相关 6xxx
    STORAGE_POOL_NOT_FOUND(6001, "存储池不存在"),
    STORAGE_POOL_ROOT_NOT_SET(6002, "存储池根目录未设置"),

    // 镜像相关 7xxx
    IMAGE_NOT_FOUND(7001, "镜像不存在"),
    IMAGE_TRANSFER_FAILED(7002, "镜像传输失败"),
    IMAGE_REF_NOT_ALLOWED(7003, "镜像不在可选范围"),

    // 文件传输 8xxx
    FILE_TRANSFER_FAILED(8001, "文件传输失败"),
    FILE_CHECKSUM_MISMATCH(8002, "文件校验和不一致"),

    // 工单 9xxx
    TICKET_NOT_FOUND(9001, "工单不存在"),

    // 公告/通知 10xxx
    ANNOUNCEMENT_NOT_FOUND(10001, "公告不存在"),
    NOTIFICATION_NOT_FOUND(10002, "通知不存在");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
