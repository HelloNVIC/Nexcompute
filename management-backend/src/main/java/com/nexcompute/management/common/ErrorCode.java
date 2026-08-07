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
    NOTIFICATION_NOT_FOUND(10002, "通知不存在"),

    // NAS 分配 11xxx
    NAS_INVITATION_NOT_FOUND(11001, "邀请不存在"),
    NAS_INVITATION_INVALID(11002, "邀请令牌无效"),
    NAS_INVITATION_EXPIRED(11003, "邀请已过期"),
    NAS_INVITATION_REVOKED(11004, "邀请已失效"),
    NAS_INVITATION_EXHAUSTED(11005, "邀请名额已用完"),
    NAS_REGISTRATION_NOT_FOUND(11006, "注册申请不存在"),
    NAS_REGISTRATION_INVALID_STATE(11007, "申请状态不允许此操作"),
    NAS_USERNAME_TAKEN(11008, "用户名已被占用"),
    NAS_NO_PASSWORD(11009, "缺少暂存密码"),
    NAS_PASSWORD_DECRYPT_FAILED(11010, "密码解密失败"),
    NAS_PROVISION_FAILED(11011, "TrueNAS 开通失败"),
    NAS_CONFIG_ERROR(11012, "NAS 模块配置缺失"),

    // NewAPI 分配 12xxx（newapi-user-allocation，镜像 NAS 11xxx）
    NEWAPI_INVITATION_NOT_FOUND(12001, "邀请不存在"),
    NEWAPI_INVITATION_INVALID(12002, "邀请令牌无效"),
    NEWAPI_INVITATION_EXPIRED(12003, "邀请已过期"),
    NEWAPI_INVITATION_REVOKED(12004, "邀请已失效"),
    NEWAPI_INVITATION_EXHAUSTED(12005, "邀请名额已用完"),
    NEWAPI_REGISTRATION_NOT_FOUND(12006, "注册申请不存在"),
    NEWAPI_REGISTRATION_INVALID_STATE(12007, "申请状态不允许此操作"),
    NEWAPI_USERNAME_TAKEN(12008, "用户名已被占用"),
    NEWAPI_NO_PASSWORD(12009, "缺少暂存密码"),
    NEWAPI_PASSWORD_DECRYPT_FAILED(12010, "密码解密失败"),
    NEWAPI_PROVISION_FAILED(12011, "NewAPI 开通失败"),
    NEWAPI_CONFIG_ERROR(12012, "NewAPI 模块配置缺失");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
