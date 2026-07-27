## MODIFIED Requirements

### Requirement: 受控端远程升级

管理员 SHALL 能在管理端"受控端升级"模块上传新版受控端 exe 文件并选择目标物理实例进行升级，支持批量升级与单独升级。升级时 SHALL 保留受控端配置文件。管理端 SHALL 记录每个物理实例的当前版本与升级目标版本。升级成功判定 SHALL 以受控端重启后经心跳回传的目标版本为准：心跳回传 `agentVersion == 目标版本` 即判定 SUCCESS；等待版本回传的超时 SHALL 不少于 180 秒，超时未回传目标版本才判定 FAILED。管理端 SHALL 区分"升级命令下发失败"与"等待版本回传超时"两类失败。

#### Scenario: 管理员上传新版 exe
- **WHEN** 管理员在"受控端升级"模块上传新版受控端 exe
- **THEN** 系统保存该 exe 并计算记录其 MD5
- **AND** 该版本对管理员可选为目标升级版本

#### Scenario: 批量升级
- **WHEN** 管理员选择多个物理实例并触发批量升级至某版本
- **THEN** 管理端依次向各实例下发升级命令
- **AND** 单个实例升级失败不影响其他实例的升级
- **AND** 升级结果（成功/失败）在管理端可见

#### Scenario: 单独升级
- **WHEN** 管理员对单个物理实例触发升级至某版本
- **THEN** 管理端向该实例下发升级命令
- **AND** 升级结果在管理端可见

#### Scenario: 升级保留配置
- **WHEN** 受控端执行升级
- **THEN** 受控端仅替换自身可执行文件
- **AND** 配置文件、Env 文件夹、存储池根目录均保留不变

#### Scenario: 心跳回传目标版本判定成功
- **WHEN** 受控端升级后重启并经心跳回传 `agentVersion == 目标版本`
- **THEN** 管理端判定该升级任务为 SUCCESS
- **AND** 不因受控端退出致 WebSocket 断开而误判失败

#### Scenario: 超时未回传目标版本判定失败
- **WHEN** 升级命令下发后超过 180 秒未收到目标版本心跳回传
- **THEN** 管理端判定该升级任务为 FAILED
- **AND** 失败信息标明为等待版本回传超时

## ADDED Requirements

### Requirement: 受控端升级进度分阶段回传

受控端 SHALL 在执行 `agent.upgrade` 命令过程中分阶段回传进度消息（`type:"progress"`，含 commandId、stage、percent），区分五个阶段：①下发文件中（downloading）、②校验中（verifying）、③备份中（backing_up）、④替换重启中（replacing）、⑤等待版本确认中（waiting）。受控端在原进程存活期间（①②③④前段）回传进度，④末段与⑤因进程退出由管理端据实情推断。管理端 SHALL 经 WebSocket 命令通道识别进度消息，更新任务阶段与各段百分比，且进度消息 SHALL 不完成（complete）原命令的等待 future。

#### Scenario: 下发文件进度回传
- **WHEN** 受控端下载新版 exe 过程中
- **THEN** 受控端回传 downloading 阶段进度，percent 为已下载字节 / 总字节

#### Scenario: 校验进度回传
- **WHEN** 受控端完成下载进入 MD5 校验
- **THEN** 受控端回传 verifying 阶段进度
- **AND** 因校验秒级完成，percent 瞬时由 0 到 100

#### Scenario: 备份进度回传
- **WHEN** 受控端备份当前 exe 过程中
- **THEN** 受控端回传 backing_up 阶段进度，percent 为已复制字节 / 总字节

#### Scenario: 替换重启段推断
- **WHEN** 受控端回传 replacing 后优雅退出
- **THEN** 管理端推断 replacing 段进度，按已耗时 / 预期 30 秒线性插值
- **AND** 检测到受控端进程退出即该段达 100%

#### Scenario: 等待版本确认段推断
- **WHEN** 新版受控端首次心跳到达
- **THEN** 管理端推断 waiting 段进度，自首次心跳起算
- **AND** 心跳 `agentVersion == 目标版本` 即该段达 100% 并判定 SUCCESS

### Requirement: 升级进度五段独立展示

管理端前端 SHALL 以五段独立进度条展示升级进度，每段独立计算 0–100%：当前阶段在跑、已完成阶段锁定 100% 并标记完成、未开始阶段为空。`AgentUpgradeTask` SHALL 记录当前阶段（progressStage）与各段百分比（stagePercents）。

#### Scenario: 五段独立进度条展示
- **WHEN** 管理员查看进行中的升级任务
- **THEN** 界面展示五个阶段各自的进度条
- **AND** 已完成段显示 100% 并标记完成
- **AND** 当前段显示进行中的百分比
- **AND** 未开始段显示 0%

#### Scenario: 异常态版本未对上
- **WHEN** 新版受控端已重启但心跳 `agentVersion != 目标版本`
- **THEN** waiting 段进度条持续爬升而非瞬时满
- **AND** 超时后该段停最后值并判定 FAILED，暴露版本未对上异常
