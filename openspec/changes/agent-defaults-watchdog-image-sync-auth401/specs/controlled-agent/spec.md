# Spec Delta

## MODIFIED Requirements

### Requirement: 存储池根目录设置与保护

受控端 SHALL 允许设置存储池根目录，根目录默认值 SHALL 为 `D:\lab404`：配置文件不存在（新装）或其中 storageRoot 为空（存量）时，受控端 SHALL 自动以默认值补填并持久化，补填 SHALL 不视为锁定（用户仍可经密码校验修改）。设置或修改存储池根目录 SHALL 均需输入「本地管理员密码」（与退出受控端密码相同），首次设置与后续修改同等受保护。根目录经用户设置后 MUST 不可直接修改，须经验密码方可变更。所有存储池在此根目录下创建。受控端 SHALL 在用户点击选择根目录文件夹时即弹出本地管理员密码输入框，密码正确方允许进入文件夹选择。

#### Scenario: 新装默认根目录
- **WHEN** 受控端首次启动（本地无配置文件）
- **THEN** 存储池根目录为默认值 `D:\lab404` 并写入配置文件
- **AND** 根目录处于未锁定状态

#### Scenario: 存量空值自动补填
- **WHEN** 受控端启动时配置文件存在但 storageRoot 为空
- **THEN** 受控端自动补填为默认值 `D:\lab404` 并持久化到配置文件
- **AND** storageRootLocked 保持 false

#### Scenario: 默认值仍可修改
- **WHEN** 根目录为自动补填的默认值（未锁定），用户经密码校验后修改
- **THEN** 受控端按「修改存储池根目录」流程保存新路径

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

## ADDED Requirements

### Requirement: 本地配置持久化稳定性

受控端本地配置文件 SHALL 仅在配置内容实际发生变化时写盘。周期性心跳回包（如全局管理员密码补推）携带的数据与本地当前值一致时，受控端 SHALL 不触发配置文件重写，保证运行期间手动编辑的配置不被周期性覆盖。

#### Scenario: 密码未变不重写配置
- **WHEN** 心跳回包的全局管理员密码与本地保存值相同
- **THEN** 受控端不重写本地配置文件

#### Scenario: 密码变更才写盘
- **WHEN** 心跳回包的全局管理员密码与本地保存值不同
- **THEN** 受控端更新内存配置并持久化到配置文件

### Requirement: Docker Desktop 常驻看门狗

受控端运行期间 SHALL 每 30 秒检查一次 Docker daemon 可达性，不可达时自动拉起 Docker Desktop。拉起后 SHALL 在 60 秒冷却期内不重复拉起（等待 daemon 启动就绪，防止启动期进程风暴）。拉起失败（如未安装 Docker Desktop）SHALL 按限流频率记录日志，不随检查周期刷屏。看门狗自受控端启动即开始检查，不设启动延迟。

#### Scenario: daemon 意外退出自动拉起
- **WHEN** Docker Desktop 意外退出导致 daemon 不可达
- **THEN** 受控端在 30 秒内检测到并自动拉起 Docker Desktop

#### Scenario: 拉起后冷却防重复
- **WHEN** 受控端刚拉起 Docker Desktop 且 daemon 尚未就绪
- **THEN** 60 秒冷却期内不再次拉起，冷却结束后继续按周期复查

#### Scenario: 拉起失败日志限流
- **WHEN** 机器未安装 Docker Desktop（找不到可执行文件），拉起持续失败
- **THEN** 看门狗按限流频率记录错误日志，不每 30 秒产生一条新日志

#### Scenario: daemon 正常时不动作
- **WHEN** Docker daemon 可达
- **THEN** 看门狗不采取任何拉起动作
