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

受控端 SHALL 允许设置存储池根目录。设置或修改存储池根目录 SHALL 均需输入「本地管理员密码」（与退出受控端密码相同），首次设置与后续修改同等受保护。根目录设置后 MUST 不可直接修改，须经验密码方可变更。所有存储池在此根目录下创建。受控端 SHALL 在用户点击选择根目录文件夹时即弹出本地管理员密码输入框，密码正确方允许进入文件夹选择。

#### Scenario: 设置存储池根目录
- **WHEN** 用户在受控端首次设置存储池根目录，点击选择文件夹
- **THEN** 受控端弹出本地管理员密码输入框
- **AND** 密码正确则允许选择并保存根目录路径，密码错误则拒绝

#### Scenario: 根目录修改需密码
- **WHEN** 存储池根目录已设置，用户尝试修改，点击选择文件夹
- **THEN** 受控端弹出本地管理员密码输入框
- **AND** 密码正确则允许修改，密码错误则拒绝

#### Scenario: 后续存储池在根目录下创建
- **WHEN** 用户已设置根目录
- **THEN** 后续所有存储池在此目录下创建

### Requirement: 受控端命令执行与结果回传

受控端 SHALL 执行管理端通过 WebSocket 派发的命令（Docker 管理、系统控制、日志查看等），并及时回传命令执行结果。受控端 SHALL 严格校验命令来源，仅执行经鉴权的管理端命令。受控端 SHALL 支持在命令执行过程中回传进度消息（`type:"progress"`，含 commandId、stage、percent），用于长时操作（如升级）的阶段进度反馈。

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

#### Scenario: 长时操作回传进度
- **WHEN** 受控端执行长时命令（如 `agent.upgrade`）过程中完成某阶段
- **THEN** 受控端经 WebSocket 回传进度消息，含 commandId、stage、percent
- **AND** 进度消息不替代最终结果回传

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

### Requirement: 受控端本地日志落盘与保留

受控端 SHALL 将运行日志落盘至存储池根目录下的 `log` 文件夹，按日分割为 `agent-YYYY-MM-DD.log` 文件。日志 SHALL 保留近 30 天，超过 30 天的日志文件 SHALL 被清理。存储池根目录未设置时，受控端 SHALL 回退将日志落至 `%LOCALAPPDATA%/Nexcompute/log/`，待根目录设置后切换至根目录 `log` 文件夹。

#### Scenario: 日志按日落盘
- **WHEN** 受控端运行并产生日志
- **THEN** 日志写入 `<存储池根目录>/log/agent-YYYY-MM-DD.log`
- **AND** 跨日时新建次日文件

#### Scenario: 保留 30 天清理
- **WHEN** 受控端启动或定时检查
- **THEN** 受控端扫描 `log` 目录
- **AND** 删除修改时间早于 30 天前的日志文件

#### Scenario: 根目录未设回退
- **WHEN** 受控端启动且存储池根目录未设置
- **THEN** 受控端将日志落至 `%LOCALAPPDATA%/Nexcompute/log/`
- **AND** 根目录设置后切换写入路径

### Requirement: 受控端中文/非 ASCII 路径检查

受控端 SHALL 在启动时检查自身可执行文件所在路径不含任何非 ASCII 字符。受控端 SHALL 在用户选择存储池根目录时检查所选路径不含任何非 ASCII 字符。检测到非 ASCII 字符时 SHALL 拒绝操作并提示路径异常，不进行编码转换兼容。

#### Scenario: 启动路径含非 ASCII 拒绝
- **WHEN** 受控端启动且自身可执行文件路径含非 ASCII 字符
- **THEN** 受控端弹窗提示路径异常
- **AND** 受控端拒绝启动

#### Scenario: 根目录含非 ASCII 拒绝
- **WHEN** 用户选择存储池根目录路径含非 ASCII 字符
- **THEN** 受控端提示路径异常
- **AND** 受控端拒绝设置该路径

### Requirement: 受控端日志远端查看命令

受控端 SHALL 响应管理端通过 WebSocket 派发的 `agent.log` 命令，按日期范围返回日志文件列表与内容。受控端 SHALL 支持返回指定日期的日志文件尾 N 行（避免大文件整文件回传），并支持经 file-transfer 提供整文件下载。

#### Scenario: 列出日志文件
- **WHEN** 管理端向受控端下发 `agent.log` 命令请求日志文件列表
- **THEN** 受控端返回 `log` 目录下的文件列表（文件名、大小、修改时间）

#### Scenario: 查看指定日期日志尾行
- **WHEN** 管理端请求某日期的日志内容（尾 N 行）
- **THEN** 受控端读取该日期日志文件的尾 N 行回传
- **AND** 不回传整文件内容

### Requirement: 受控端双排布局界面

受控端 GUI SHALL 采用双排（双列）布局展示内容，窗口宽度加宽以容纳双列信息。

#### Scenario: 双排布局展示
- **WHEN** 受控端 GUI 窗口打开
- **THEN** 界面以双列布局展示状态与功能
- **AND** 窗口宽度较单排布局加宽

### Requirement: 受控端单实例运行

受控端 SHALL 保证同一机器上同时仅运行一个受控端进程。受控端启动时 SHALL 检测是否已有受控端实例在运行：若已存在，新启动的进程 SHALL 自动退出（记录日志提示重复启动），保留已在运行的实例；若不存在，则持有单实例标记并正常启动，退出时释放该标记。因崩溃等异常退出后，下次启动 SHALL 仍能正常获得单实例标记并启动。

#### Scenario: 重复启动自动退出
- **WHEN** 受控端已在运行，用户再次启动受控端可执行文件
- **THEN** 新进程检测到已有实例，记录日志后自动退出
- **AND** 原有实例不受影响，机器上仍只有一个受控端进程

#### Scenario: 正常退出后可再次启动
- **WHEN** 受控端正常退出（含升级触发的退出）释放单实例标记
- **THEN** 下次启动检测无已有实例，正常启动并持有标记

#### Scenario: 异常退出后可再次启动
- **WHEN** 上一个受控端进程因崩溃或被强杀异常退出
- **THEN** 下次启动仍能正常启动，不因残留标记被永久阻塞

### Requirement: 受控端私有镜像仓库配置引导

受控端环境准备栏目 SHALL 提供一个"配置私有镜像仓库"按钮：点击后弹窗展示需在 Docker daemon 配置（daemon.json / Docker Engine 设置）中追加的内容，并提供一键复制。复制内容 SHALL 为 `,"insecure-registries": ["10.13.66.25:5000"]`（含前导逗号，便于追加粘贴到既有 JSON 的最后一项之后）。弹窗 SHALL 说明粘贴位置，并提示当配置为空对象时去掉前导逗号。

#### Scenario: 点击按钮展示并复制
- **WHEN** 用户点击环境准备栏目的"配置私有镜像仓库"按钮
- **THEN** 弹窗展示 `,"insecure-registries": ["10.13.66.25:5000"]` 与粘贴指引
- **AND** 点击"一键复制"后剪贴板内容为该字符串（含前导逗号）

