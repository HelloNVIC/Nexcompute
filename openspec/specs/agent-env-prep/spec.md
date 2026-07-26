# agent-env-prep Specification

## Purpose
受控端环境准备能力：管理端集中托管环境准备所需文件（Docker Desktop Installer、NVIDIA Container Toolkit deb 包等），受控端按 MD5 同步至本地存储池 `Env` 文件夹；受控端 GUI 提供 9 个分步按钮拉起管理员 PowerShell 执行 `docs/受控端环境准备.md` 各章节脚本（窗口保持开启），离线 NVIDIA Toolkit 与 Docker Engine 配置步骤提供引导弹窗与一键复制。
## Requirements
### Requirement: 受控端环境准备分步执行

受控端 SHALL 在 GUI 提供"受控端环境准备"栏目，按 `docs/受控端环境准备.md` 的 9 个章节提供 9 个分步按钮（一一对应，不合并）。用户点击按钮后 SHALL 新建一个管理员权限的 PowerShell 窗口并按行执行该步骤脚本，执行完成后 SHALL 保持窗口打开（不自动关闭）。按钮 SHALL 覆盖：安装 Docker、安装 WSL 环境、检查显卡驱动、离线安装 NVIDIA Container Toolkit、验证 Toolkit 并配置运行时、修改 Docker Engine 配置、创建 GPU 容器并启动 Jupyter、环境测试、GPU 压力测试。

#### Scenario: 点击按钮拉起管理员 PowerShell
- **WHEN** 用户在"受控端环境准备"栏目点击某步骤按钮
- **THEN** 受控端新建一个管理员权限的 PowerShell 窗口
- **AND** 在窗口中按行执行该步骤对应的脚本
- **AND** 脚本执行完成后窗口保持打开，不自动关闭

#### Scenario: 步骤脚本与文档章节对应
- **WHEN** 受控端渲染"受控端环境准备"栏目
- **THEN** 栏目展示 9 个按钮，分别对应文档的 9 个章节
- **AND** 每个按钮执行的脚本与对应章节内容一致

### Requirement: 管理端环境文件托管与 MD5 同步

管理员 SHALL 能在管理端"受控端环境"模块上传受控端环境准备所需文件（如 `Docker Desktop Installer.exe`、NVIDIA Container Toolkit 各 deb 包）。受控端 SHALL 按文件 MD5 将文件同步至本地存储池根目录下的 `Env` 文件夹，仅下载缺失或 MD5 不一致的文件。管理员 SHALL 能通过"环境网盘同步"按钮手动触发同步。

#### Scenario: 管理员上传环境文件
- **WHEN** 管理员在"受控端环境"模块上传一个环境文件
- **THEN** 系统保存文件至管理端存储并计算记录其 MD5
- **AND** 文件对受控端可见并可同步

#### Scenario: 受控端按 MD5 增量同步
- **WHEN** 受控端执行环境文件同步
- **THEN** 受控端对比本地 `Env` 文件夹中文件 MD5 与管理端清单
- **AND** 仅下载缺失或 MD5 不一致的文件并校验完整性
- **AND** 已存在且 MD5 一致的文件不重复下载

#### Scenario: 手动触发同步
- **WHEN** 管理员点击"环境网盘同步"按钮
- **THEN** 管理端向受控端下发同步命令
- **AND** 受控端立即执行一次环境文件同步

### Requirement: 离线 NVIDIA Container Toolkit 安装引导

"离线安装 NVIDIA Container Toolkit"步骤 SHALL 不直接执行安装，而是弹出提示框引导用户在 WSL 内安装。提示框 SHALL 展示依赖顺序的安装命令，并提供"一键复制"按钮将命令复制到剪贴板。提示框 SHALL 提示用户所需 deb 包位于本地 `Env` 文件夹、需先复制到 WSL 用户目录。

#### Scenario: 弹出引导提示框
- **WHEN** 用户点击"离线安装 NVIDIA Container Toolkit"按钮
- **THEN** 受控端弹出提示框
- **AND** 提示框展示按依赖顺序的 dpkg 安装命令
- **AND** 提示框说明 deb 包位于本地 `Env` 文件夹、需先复制到 WSL 用户目录

#### Scenario: 一键复制安装命令
- **WHEN** 用户在提示框点击"一键复制"按钮
- **THEN** 安装命令被复制到系统剪贴板
- **AND** 用户可在 WSL 内粘贴执行

### Requirement: Docker Engine 配置引导

"修改 Docker Engine 配置"步骤 SHALL 先启动 Docker Desktop，再弹出提示框引导用户在 Docker Desktop 的 Docker Engine 设置中粘贴配置。提示框 SHALL 展示 JSON 配置代码块并提供"一键复制"按钮。

#### Scenario: 启动 Docker Desktop 并引导
- **WHEN** 用户点击"修改 Docker Engine 配置"按钮
- **THEN** 受控端启动 Docker Desktop
- **AND** 受控端弹出提示框展示 JSON 配置与操作指引

#### Scenario: 一键复制 JSON 配置
- **WHEN** 用户在提示框点击"一键复制"按钮
- **THEN** JSON 配置被复制到系统剪贴板
- **AND** 用户可在 Docker Desktop 的 Docker Engine 设置中粘贴
