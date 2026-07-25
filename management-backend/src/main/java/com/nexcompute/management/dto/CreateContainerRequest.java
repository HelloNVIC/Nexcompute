package com.nexcompute.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 容器创建请求（任务 10.3，增强：项目名、环境变量、端口、存储池必选）
 */
@Data
public class CreateContainerRequest {

    @NotNull
    private Long instanceId;

    @NotBlank(message = "镜像不能为空")
    private String imageRef;

    @NotNull(message = "存储池不能为空")
    private Long storagePoolId;

    /** 项目名（用于容器命名：学号-项目名-随机串） */
    @NotBlank(message = "项目名不能为空")
    private String projectName;

    @NotBlank(message = "SSH 密码不能为空")
    private String sshPassword;

    /** CPU 硬限制（核数） */
    private Float cpuLimit;

    /** 内存硬限制（字节） */
    private Long memoryLimit;

    /** GPU 显存软限制（MB） */
    private Integer gpuMemoryLimit;

    /** /dev/shm 大小（字节） */
    private Long shmSize;

    /** 声明的容器内端口（SSH 22 自动包含，系统自动分配宿主端口避免冲突） */
    private List<Integer> containerPorts;

    /** 自定义环境变量（KEY=VALUE 格式） */
    private List<String> env;

    /** 原始表单配置快照（JSON，供「容器配置」回显） */
    private String formSnapshot;

    /** 备注（platform-refinements #1） */
    private String remark;
}
