# image-management Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 容器提交为镜像

用户 SHALL 能将自己的容器提交（commit）为镜像。提交时 SHALL 指定镜像名、标签、项目与备注。受控端执行 `docker commit` 生成本地镜像，再执行 `docker save` 导出 tar，tar 文件命名 SHALL 为 `工号-项目-镜像名-标签-备注-随机串`。受控端 SHALL 将 tar 上传至管理端，**回传全部完成后**方记为持久化成功；回传未完成时镜像状态为上传中，不可被使用。管理端存储 tar 文件并记录镜像元数据（归属用户与工号、名称、标签、项目、备注、大小、来源容器）。

#### Scenario: 提交容器为镜像
- **WHEN** 用户选择一个自己拥有的容器，点击"提交镜像持久化"并填写镜像名/标签/项目/备注
- **THEN** 受控端执行 `docker commit` 生成本地镜像
- **AND** 受控端执行 `docker save` 导出 tar，文件命名为 `工号-项目-镜像名-标签-备注-随机串`
- **AND** 受控端将 tar 上传至管理端
- **AND** 管理端存储 tar 并记录镜像元数据（归属、名称、标签、项目、备注、来源容器）

#### Scenario: 回传完成才记为成功
- **WHEN** tar 上传进行中
- **THEN** 镜像状态为上传中，不可被用于创建容器
- **AND** 仅当回传全部完成，镜像状态置为就绪，记为持久化成功
- **AND** 回传失败则记为失败，不产出可用镜像
### Requirement: 镜像归属与可见性

镜像 SHALL 归属于创建它的用户。用户通过容器提交产生的镜像默认仅对本人与管理员可见，不对导师默认可见（需显式共享）。管理员通过上传接口上传的镜像 SHALL 对所有用户可见。管理员可见所有用户的镜像。用户可共享自己的镜像给其他用户（按工号精准匹配）或共享给整个课题组。

#### Scenario: commit 镜像默认仅本人与管理员可见
- **WHEN** 用户通过容器提交产生一个镜像且未显式共享
- **THEN** 该镜像仅对本人与管理员可见
- **AND** 导师默认不可见该镜像

#### Scenario: 管理员上传镜像全用户可见
- **WHEN** 管理员通过上传接口上传一个镜像
- **THEN** 所有用户在镜像列表中均可见该镜像
- **AND** 所有用户可使用该镜像创建容器

#### Scenario: 管理员全镜像可见
- **WHEN** 管理员浏览镜像列表
- **THEN** 列表包含所有用户的镜像

#### Scenario: 按工号精准共享镜像
- **WHEN** 用户输入目标用户工号共享自己的镜像
- **THEN** 系统按工号精准匹配目标用户
- **AND** 匹配成功则被共享用户在镜像列表中可见并可使用该镜像
- **AND** 工号不匹配则拒绝并提示无此用户

#### Scenario: 共享镜像给课题组
- **WHEN** 用户将自己的镜像共享给整个课题组
- **THEN** 该课题组所有成员在镜像列表中可见该镜像
- **AND** 课题组成员可使用该镜像创建容器
### Requirement: 镜像 tar 导出导入

镜像 SHALL 以 tar 文件形式在管理端存储与分发。管理端 SHALL 在创建容器前下发 `image.load`：受控端检查本地是否已持有该镜像，已持有则跳过，否则经 file-transfer 下载 tar 并执行 `docker load` 导入；导入成功后再创建并启动容器。无需管理端维护已同步镜像记录（由受控端本地存在性检查判定）。

#### Scenario: 使用非本地镜像创建容器
- **WHEN** 用户选择一个目标受控端本地尚未持有的镜像创建容器
- **THEN** 管理端将镜像 tar 传输至目标受控端（复用 file-transfer）
- **AND** 受控端执行 `docker load` 导入镜像
- **AND** 导入完成后创建并启动容器
- **AND** 受控端随后已本地持有该镜像

#### Scenario: 使用已本地镜像创建容器
- **WHEN** 用户选择的镜像已在目标受控端本地存在
- **THEN** 直接创建容器，不重复传输 tar

#### Scenario: 镜像分发失败中止
- **WHEN** tar 传输或 `docker load` 失败
- **THEN** 容器创建被中止并返回错误
### Requirement: 公共镜像库管理

管理员 SHALL 在管理端维护公共镜像库。公共镜像 tar 存储于管理端。受控端 SHALL 在启动时及每隔固定时间自动同步公共镜像库（拉取新增/更新镜像 tar 并 `docker load`）。

#### Scenario: 管理员上传公共镜像
- **WHEN** 管理员上传一个基础镜像 tar 到公共镜像库
- **THEN** 管理端存储 tar 并记录为公共镜像
- **AND** 各受控端下次同步时自动拉取并导入

#### Scenario: 受控端启动同步
- **WHEN** 受控端启动并连接管理端
- **THEN** 受控端向管理端查询公共镜像列表
- **AND** 对本地缺失的公共镜像，下载 tar 并 `docker load`

#### Scenario: 定时同步
- **WHEN** 受控端运行中且到达同步间隔
- **THEN** 受控端查询公共镜像列表并同步新增/更新的镜像

#### Scenario: 用户选择公共镜像创建容器
- **WHEN** 用户在容器创建表单中选择公共镜像
- **THEN** 该镜像已在受控端本地（因自动同步），可直接创建容器


### Requirement: 镜像应用端口与 tar 自动解析

管理员/用户增加镜像时，SHALL 支持拖拽上传 tar 文件，并可为镜像声明"应用端口"（支持多个）。上传时管理端 SHALL 自动解析 tar 文件内的镜像标签（RepoTags）与暴露端口（ExposedPorts），据此预填镜像名称、标签与应用端口。镜像元数据 SHALL 持久化应用端口列表，供容器创建时自动填入"容器内端口"。

#### Scenario: 拖拽上传 tar 并自动解析
- **WHEN** 用户拖拽一个 tar 文件到上传区并提交
- **THEN** 管理端解析 tar 的 RepoTags 与 ExposedPorts
- **AND** 预填镜像名称与标签（可由用户修改）
- **AND** 预填应用端口为解析到的暴露端口
- **AND** 镜像元数据保存应用端口列表

#### Scenario: 声明多个应用端口
- **WHEN** 用户为镜像声明多个应用端口（如 8888、6006）
- **THEN** 系统保存该镜像的多个应用端口
- **AND** 创建容器时这些端口自动填入"容器内端口"

#### Scenario: 无 ExposedPorts 的镜像
- **WHEN** 上传的 tar 不含 ExposedPorts
- **THEN** 应用端口为空，用户可手动添加

### Requirement: 公共镜像同步的受控端鉴权可达

管理端 SHALL 向受控端提供公共镜像列表与 tar 下载接口，且受控端 SHALL 能以其 agent 凭证（agent token + 物理实例编号）访问这些接口，无需用户 JWT。管理端不得因受控端使用 agent 凭证而返回空体或拒绝，以避免受控端 `json.Decode` 出现 `EOF`。

#### Scenario: 受控端以 agent 凭证查询公共镜像列表
- **WHEN** 受控端启动或到达同步间隔，以 agent token 与实例编号请求管理端公共镜像列表
- **THEN** 管理端返回该受控端可见的公共镜像列表（含名称、标签、tar 路径、校验和）
- **AND** 响应体为合法 JSON，受控端能成功解析

#### Scenario: 受控端以 agent 凭证下载公共镜像 tar
- **WHEN** 受控端对本地缺失的公共镜像请求下载 tar
- **THEN** 管理端经 file-transfer 鉴权后提供 tar 下载
- **AND** 受控端下载并 `docker load` 成功

### Requirement: 镜像上传解析与使用说明

用户上传 tar 镜像时，管理端 SHALL 在文件选定后即时解析 tar 的 `RepoTags` 与 `ExposedPorts`，并将解析结果回填至名称、标签、应用端口文本框供用户确认或修改。上传时 SHALL 提供"使用说明"字段，存入镜像元数据并在镜像列表展示。

#### Scenario: 选定 tar 即时解析回填
- **WHEN** 用户在选择镜像 tar 文件后、提交上传前
- **THEN** 管理端解析 tar 的 `RepoTags` 与 `ExposedPorts`
- **AND** 将解析得到的名称/标签/应用端口回填至对应文本框
- **AND** 用户可在提交前修改这些值

#### Scenario: 使用说明字段
- **WHEN** 用户上传 tar 时填写"使用说明"
- **THEN** 管理端将使用说明存入镜像元数据
- **AND** 镜像列表展示该说明
