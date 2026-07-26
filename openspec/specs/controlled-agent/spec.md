# controlled-agent Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 受控端可视化界面与托盘常驻

受控端 SHALL 以系统托盘常驻运行，提供可视化窗口展示当前系统状态、Docker 环境检查结果、控制端 IP 设置、存储池根目录设置。关窗口时 SHALL 缩到托盘不退出。

#### Scenario: 查看系统状态
- **WHEN** 受控端运行中
- **THEN** GUI 展示与管理端的连接状态、心跳状态、当前物理实例编号

#### Scenario: 关窗口缩到托盘
- **WHEN** 用户关闭受控端窗口
- **THEN** 受控端缩到系统托盘继续运行，不退出

#### Scenario: 设置控制端 IP
- **WHEN** 用户在受控端 GUI 中设置控制端（管理端）IP 地址
- **THEN** 受控端保存 IP 并使用该地址连接管理端

### Requirement: 管理员权限与开机自启

受控端 SHALL 在启动时获取整体管理员权限运行。受控端 SHALL 开机自动启动。

#### Scenario: 以管理员权限启动
- **WHEN** 受控端启动
- **THEN** 受控端以管理员权限运行（用于 Docker 操作、系统重启、息屏、PowerShell 等命令执行）

#### Scenario: 开机自启
- **WHEN** Windows 系统启动/用户登录
- **THEN** 受控端自动启动

### Requirement: 本地管理员密码保护

受控端 SHALL 使用「本地管理员密码」保护关键本地操作（退出受控端、修改存储池根目录等受保护配置）。该密码 SHALL 为全局共享——所有受控端共用同一个密码，由管理员在管理端统一设置后下发至所有受控端。密码 SHALL 明文传输、明文本地保存。受控端在执行受保护操作时 SHALL 弹出密码输入提示，密码正确方允许操作。

#### Scenario: 退出受控端需密码
- **WHEN** 用户尝试退出受控端
- **THEN** 受控端弹出密码输入框
- **AND** 密码正确则退出，密码错误则拒绝退出

#### Scenario: 修改受保护配置需密码
- **WHEN** 用户尝试修改存储池根目录等受保护配置
- **THEN** 受控端弹出本地管理员密码输入框
- **AND** 密码正确则允许修改，密码错误则拒绝

#### Scenario: 管理员统一下发全局密码
- **WHEN** 管理员在管理端统一设置受控端管理密码
- **THEN** 管理端将该密码明文下发至所有受控端
- **AND** 各受控端明文本地保存该密码
### Requirement: Docker 环境检查

受控端 SHALL 能检查当前 Docker 环境，验证 Docker 是否可用、GPU/Toolkit 是否配置正确。

#### Scenario: Docker 可用性检查
- **WHEN** 用户在受控端触发 Docker 环境检查
- **THEN** 受控端检测 Docker Desktop 是否运行、Docker API 是否可本地调用
- **AND** GUI 展示检查结果（可用/不可用及原因）

#### Scenario: GPU 支持检查
- **WHEN** Docker 环境检查执行
- **THEN** 受控端检测 NVIDIA Container Toolkit 是否配置、GPU 是否可被容器访问
- **AND** GUI 展示 GPU 检测结果

### Requirement: 防止系统睡眠休眠

受控端运行期间 SHALL 防止 Windows 系统进入睡眠或休眠状态。

#### Scenario: 运行时阻止睡眠
- **WHEN** 受控端运行中
- **THEN** 系统不进入睡眠或休眠（通过 Windows API SetThreadExecutionState 等机制）

#### Scenario: 受控端退出后恢复
- **WHEN** 受控端退出
- **THEN** 系统恢复默认电源管理行为（允许睡眠休眠）

### Requirement: 存储池根目录设置与保护

受控端 SHALL 允许设置存储池根目录。根目录设置后 MUST 不可直接修改。如需修改，SHALL 输入「本地管理员密码」（与退出受控端密码相同）。所有存储池在此根目录下创建。

#### Scenario: 设置存储池根目录
- **WHEN** 用户在受控端首次设置存储池根目录
- **THEN** 受控端保存根目录路径
- **AND** 后续所有存储池在此目录下创建

#### Scenario: 根目录修改需密码
- **WHEN** 存储池根目录已设置，用户尝试修改
- **THEN** 受控端弹出本地管理员密码输入框
- **AND** 密码正确则允许修改，密码错误则拒绝

### Requirement: 受控端命令执行与结果回传

受控端 SHALL 执行管理端通过 WebSocket 派发的命令（Docker 管理、系统控制等），并及时回传命令执行结果。受控端 SHALL 严格校验命令来源，仅执行经鉴权的管理端命令。

#### Scenario: 执行 Docker 管理命令
- **WHEN** 受控端通过 WebSocket 收到 Docker 管理命令
- **THEN** 受控端在本地调用 Docker API 执行
- **AND** 执行结果通过 WebSocket 回传管理端

#### Scenario: 命令执行失败回传
- **WHEN** 受控端执行命令失败
- **THEN** 受控端回传失败信息与错误详情

#### Scenario: 拒绝未鉴权命令
- **WHEN** 受控端收到未经鉴权的命令
- **THEN** 受控端拒绝执行并记录安全日志

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

