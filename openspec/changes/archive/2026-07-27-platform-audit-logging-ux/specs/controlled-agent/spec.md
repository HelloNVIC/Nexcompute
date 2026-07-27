## MODIFIED Requirements

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

## ADDED Requirements

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
