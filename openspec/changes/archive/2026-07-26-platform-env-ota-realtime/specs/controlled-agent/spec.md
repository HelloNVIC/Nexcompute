## ADDED Requirements

### Requirement: 受控端版本标识常量

受控端 SHALL 内置由构建期 ldflags 注入的版本号常量，并经心跳上报。版本号 SHALL 反映构建产物对应的发布版本，不再硬编码。

#### Scenario: 版本号构建期注入
- **WHEN** 受控端经构建脚本构建
- **THEN** 构建脚本经 ldflags 将版本号注入受控端版本常量
- **AND** 心跳上报使用该常量值

### Requirement: 受控端环境准备栏目

受控端 GUI SHALL 提供"受控端环境准备"栏目，含 9 个分步按钮。点击按钮 SHALL 拉起管理员权限的 PowerShell 窗口按行执行该步骤脚本，执行完成后窗口保持开启不自动关闭。离线 NVIDIA Container Toolkit 与修改 Docker Engine 配置步骤 SHALL 以弹窗引导并支持一键复制相关命令/配置。

#### Scenario: 按钮拉起管理员 PowerShell 并保持窗口
- **WHEN** 用户点击环境准备某步骤按钮
- **THEN** 受控端新建管理员权限 PowerShell 窗口并按行执行脚本
- **AND** 执行完成后窗口保持打开

#### Scenario: 引导步骤弹窗与一键复制
- **WHEN** 用户点击离线安装 Toolkit 或修改 Docker Engine 配置按钮
- **THEN** 受控端弹出引导提示框
- **AND** 提示框提供一键复制按钮复制相关命令或配置

### Requirement: 受控端自更新命令执行

受控端 SHALL 执行管理端通过 WebSocket 派发的 `agent.upgrade` 命令，下载并校验新版 exe，经独立外部更新器在自身优雅退出后完成替换与重启。自更新 SHALL 保留配置文件且保证升级期间自身不崩溃。校验失败 SHALL 不替换并回传失败。

#### Scenario: 执行升级命令
- **WHEN** 受控端收到 `agent.upgrade` 命令
- **THEN** 受控端下载新版 exe 并校验 MD5
- **AND** 校验通过后备份当前 exe 并准备外部更新器
- **AND** 优雅退出由外部更新器完成替换与启动

#### Scenario: 校验失败不替换
- **WHEN** 新版 exe MD5 校验失败
- **THEN** 受控端不替换自身并回传失败
- **AND** 受控端进程继续正常运行不退出
