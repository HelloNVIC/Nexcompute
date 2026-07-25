# agent-communication Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 受控端心跳保活

受控端 SHALL 通过定时 HTTP POST 向管理端发送心跳，携带本机状态数据：CPU 占用与温度、内存占用（总量/已用）、结构化 GPU 信息（型号、总显存、已用显存、利用率、温度）、主机 IP 地址（全部地址）、进程列表、配置信息。GPU 信息 SHALL 为结构化字段而非自由文本。心跳为受控端主动发起的出站请求。管理端 SHALL 在超过阈值未收到心跳时标记受控端为离线。

#### Scenario: 正常心跳
- **WHEN** 受控端运行中且网络正常
- **THEN** 受控端按固定间隔（如 5 秒）向管理端 POST 心跳，包含当前状态数据（含主机全部 IP 与结构化 GPU）
- **AND** 管理端更新该物理实例的最后心跳时间与状态快照

#### Scenario: 心跳超时标记离线
- **WHEN** 管理端超过阈值（如 30 秒）未收到某受控端心跳
- **THEN** 管理端将该物理实例状态标记为「离线」
- **AND** 前端物理实例列表中该实例显示离线标识

#### Scenario: 完整回传 IP 与 GPU
- **WHEN** 受控端发送心跳
- **THEN** 心跳包含主机全部 IP 地址与结构化 GPU（型号、总显存、已用显存、利用率、温度）
- **AND** 管理端将结构化 GPU 显存持久化并可用于资源上限与监控
### Requirement: WebSocket 命令通道

受控端 SHALL 在启动后与管理端建立一条独立的 WebSocket 长连接（受控端主动连出），用于接收管理端派发的命令并回传执行结果。WebSocket 通道与心跳通道相互独立。

#### Scenario: 建立命令通道
- **WHEN** 受控端启动并完成心跳注册
- **THEN** 受控端向管理端发起 WebSocket 连接
- **AND** 连接建立后受控端可接收命令消息

#### Scenario: 命令派发与结果回传
- **WHEN** 管理端通过 WebSocket 向受控端发送命令消息
- **THEN** 受控端执行命令并通过同一 WebSocket 连接回传执行结果
- **AND** 管理端将结果关联到发起命令的请求上下文

#### Scenario: WebSocket 断连重连
- **WHEN** WebSocket 连接断开
- **THEN** 受控端 SHALL 继续发送心跳（心跳通道独立保活）
- **AND** 受控端按退避策略重连 WebSocket
- **AND** 重连后管理端同步受控端当前状态

### Requirement: 双向互信鉴权

受控端与管理端之间的通信 SHALL 经过双向鉴权。受控端须验证管理端身份（防止伪管理端派发恶意命令），管理端须验证受控端身份（防止流氓受控端接入）。

#### Scenario: 受控端注册鉴权
- **WHEN** 受控端首次连接管理端
- **THEN** 受控端使用预配置的凭证或证书向管理端注册
- **AND** 管理端验证凭证通过后接纳该受控端并分配物理实例编号

#### Scenario: 拒绝未授权连接
- **WHEN** 未携带有效凭证的受控端尝试连接
- **THEN** 管理端拒绝连接并记录安全日志

### Requirement: 连接模式声明

受控端 SHALL 在初始化时声明连接模式（直连 / 穿透）。连接模式为 per-machine 设置，影响容器连接信息生成方式。穿透模式本期标记为「敬请期待」，不提供功能。

#### Scenario: 选择直连模式
- **WHEN** 受控端初始化时选择「直连」
- **THEN** 该物理实例上的容器连接信息以物理机 IP + 宿主端口形式暴露给用户
- **AND** 管理端记录该实例连接模式为「直连」

#### Scenario: 选择穿透模式
- **WHEN** 受控端初始化时选择「穿透」
- **THEN** 系统标记该模式为「敬请期待」，本期不提供穿透功能


### Requirement: 容器运行状态心跳回传

受控端 SHALL 在每次心跳中携带本机各容器的运行状态（容器标识与运行/停止/退出等状态）。管理端 SHALL 据此实时更新对应容器的状态，状态变更 SHALL 通过既有 SSE 通道实时推送前端。

#### Scenario: 容器状态实时同步
- **WHEN** 受控端某容器状态发生变化（如外部停止、异常退出）
- **THEN** 受控端在下次心跳中回传最新容器状态
- **AND** 管理端更新该容器状态
- **AND** 前端经 SSE 实时收到状态更新

#### Scenario: 心跳外停止被感知
- **WHEN** 容器在管理端命令之外被停止（如在受控端直接 docker stop）
- **THEN** 下一次心跳回传该容器已停止
- **AND** 管理端状态从 RUNNING 更新为对应状态
