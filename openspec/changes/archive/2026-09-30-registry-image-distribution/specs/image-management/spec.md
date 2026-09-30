## MODIFIED Requirements

### Requirement: 容器提交为镜像

用户 SHALL 能将自己的容器提交（commit）为镜像。提交时 SHALL 指定镜像名、标签、项目与备注。受控端执行 `docker commit` 生成本地镜像后，SHALL 将其标记（tag）并推送（push）至私有仓库 10.13.66.25:5000，而不再导出 tar 回传管理端。仓库内镜像名（repo）SHALL 沿用原 tar 命名规则派生为 `工号-项目-镜像名-标签-备注-随机串`（各段 sanitize），仓库标签固定为 `latest`。系统内镜像记录的镜像名 SHALL 为该完整 repo 名。**推送全部完成后**方记为持久化成功；推送未完成时镜像状态为推送中，不可被使用。管理端 SHALL 记录镜像元数据（归属用户与工号、名称、标签、项目、备注、大小、来源容器）。

#### Scenario: 提交容器为镜像
- **WHEN** 用户选择一个自己拥有的容器，点击"提交镜像持久化"并填写镜像名/标签/项目/备注
- **THEN** 受控端执行 `docker commit` 生成本地镜像
- **AND** 受控端将其 tag 为 `10.13.66.25:5000/工号-项目-镜像名-标签-备注-随机串:latest` 并 push 至该仓库
- **AND** 管理端记录镜像元数据（归属、名称=完整 repo 名、标签=latest、项目、备注、来源容器）

#### Scenario: 回传完成才记为成功
- **WHEN** 受控端 push 进行中
- **THEN** 镜像状态为推送中，不可被用于创建容器
- **AND** 仅当 push 全部完成，镜像状态置为就绪，记为持久化成功
- **AND** push 失败则记为失败，不产出可用镜像

#### Scenario: 推送失败不产生可用记录
- **WHEN** 受控端 push 到仓库失败（网络中断或仓库拒绝）
- **THEN** 镜像记录状态置为失败
- **AND** 该镜像不出现在可用于创建容器的镜像集合中

### Requirement: 镜像 tar 导出导入

仓库类镜像 SHALL 以私有仓库拉取（pull）形式分发：管理端在创建容器前下发 `image.pull`，受控端检查本地是否已持有该镜像，已持有则跳过，否则从 10.13.66.25:5000 执行 `docker pull`（引用为 `10.13.66.25:5000/镜像名:标签`）；拉取成功后再创建并启动容器。拉取或导入失败 SHALL 中止容器创建。存量 tar 镜像（记录中含 tar 存储路径的）SHALL 继续按原 file-transfer + `docker load` 方式分发，行为不变。

#### Scenario: 使用非本地镜像创建容器
- **WHEN** 用户选择一个仓库类有效镜像创建容器且目标受控端本地尚未持有
- **THEN** 管理端下发 `image.pull`（含 `10.13.66.25:5000/镜像名:标签`）
- **AND** 受控端执行 `docker pull` 拉取镜像
- **AND** 拉取完成后创建并启动容器

#### Scenario: 使用已本地镜像创建容器
- **WHEN** 用户选择的镜像已在目标受控端本地存在
- **THEN** 直接创建容器，不重复拉取

#### Scenario: 镜像分发失败中止
- **WHEN** `docker pull` 失败（仓库不可达、镜像不存在或网络错误）
- **THEN** 容器创建被中止并返回错误

#### Scenario: 存量 tar 镜像兼容
- **WHEN** 用户选择一个存量 tar 镜像（记录含 tar 存储路径）创建容器
- **THEN** 仍按 file-transfer 下载 tar 并 `docker load` 导入后创建容器，行为与变更前一致

## REMOVED Requirements

### Requirement: 镜像上传解析与使用说明
**Reason**: tar 文件上传入口由私有仓库登记 + 用户自行推送取代（见 registry-image-management 能力）；管理端不再接收与解析用户上传的 tar。
**Migration**: 存量 tar 上传镜像仍可正常分发使用（走原 tar 路径）；使用说明、应用端口、容器内挂载与容器内挂载点字段转入镜像登记/编辑表单继续提供；`/images/parse-tar` 与 `/images/upload-tar` 接口保留仅供兼容，不再被前端使用。
