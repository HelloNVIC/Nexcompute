## Why

当前镜像以 tar 形式存于管理端磁盘，分发靠 file-transfer 逐块传输 + 受控端 `docker load`：大镜像传输慢、占管理端存储、创建容器时同步阻塞（最长 600s）且无任何进度反馈。改为内网私有仓库（10.13.66.25:5000）分发后，镜像一次推送全网可用，受控端直接 `docker pull`，并可实现真实拉取进度。同时修复受控端可被重复启动导致多进程并存的问题。

## What Changes

- **镜像分发改为私有仓库**：前端"上传镜像"改为纯元数据登记（原始镜像名、原始标签、应用端口、容器内挂载、使用说明），不再上传 tar 文件；操作列新增"上传"按钮，展示 `docker tag` + `docker push` 命令（指向 10.13.66.25:5000）供用户在自己机器上执行。
- **有效性检查**：镜像列表新增"有效性"字段，管理端经 10.13.66.25:5000 的 Registry v2 API（/v2 接口与 push/pull 同端口）检查镜像是否已推送；操作列新增"刷新状态"按钮即时更新。仅有效（仓库中存在）的镜像可用于创建容器。
- **容器提交镜像改为推送仓库**：受控端 `docker commit` 后不再 `docker save`+tar 回传，改为 `docker tag` + `docker push` 到 10.13.66.25:5000；镜像命名沿用原 tar 命名规则（工号-项目-镜像名-标签-备注-随机串）派生（具体拆分见 design 讨论）。
- **创建容器实时拉取进度**：`image.load`（tar 下载+load）替换为 `image.pull`（`docker pull` 10.13.66.25:5000/...），受控端复用既有 progress 消息通道逐层回传拉取进度，管理端经 SSE 推送给创建用户，前端实时展示。
- **无标记镜像管理**：管理员镜像管理新增"无标记镜像"视图，列出仓库中存在但系统未登记的镜像，可补录元数据（应用端口/挂载点/使用说明/可见性）转为系统内可用镜像。
- **受控端单实例**：受控端启动时检测已有实例在运行则自动退出，保证单机仅一个受控端进程。
- **受控端私有仓库配置引导**：受控端环境准备卡片新增按钮，点击弹窗展示需在 Docker daemon 配置中追加的 `insecure-registries` 内容（`,"insecure-registries": ["10.13.66.25:5000"]`，前导逗号便于追加到既有 JSON 末项后），支持一键复制。
- 存量 tar 镜像（已有 tarPath 记录）仍按原 file-transfer + `docker load` 路径分发，兼容过渡；公共镜像库存量同步逻辑保留。

## Capabilities

### New Capabilities

- `registry-image-management`：基于私有仓库的镜像登记、上传命令展示、有效性检查、无标记镜像发现与补录登记。

### Modified Capabilities

- `image-management`：镜像 tar 导出导入需求变更为仓库拉取分发（存量 tar 兼容）；容器提交为镜像的需求由 "save tar + 回传" 变更为 "tag + push 到仓库"；镜像上传入口由 tar 上传变更为元数据登记 + 用户自行推送。
- `container-lifecycle`：创建容器时的镜像分发步骤改为 `docker pull` 并向用户实时展示拉取进度（若该能力已有 spec 涉及创建流程需同步更新）。
- `controlled-agent`：受控端新增单实例约束（重复进程自动退出）。

## Impact

- **management-backend**：`ImageService`/`ImageController`（登记/有效性检查/无标记镜像）、`ContainerService.ensureImageLoaded`（改为 image.pull）、`AgentWebSocketHandler`/progress 路由（泛化 OTA progress 到通用路由 + SSE 推送）、新 `RegistryClient`（10.13.66.25:5000 /v2 API）、Flyway V35（镜像表新增仓库字段）、application.yml 新增仓库地址配置 + 两个 compose example 模板。
- **management-frontend**：`ImageListView.vue`（上传弹窗改元数据表单、上传命令弹窗、有效性列、刷新状态、无标记镜像 tab）、容器创建页拉取进度展示。
- **controlled-agent**：`image_handlers.go`（commit 改 push、新增 pull 进度回传）、`docker/image.go`（ImagePull/ImagePush）、`main.go`（单实例互斥）、`gui/envprep.go`（私有仓库配置引导按钮）、各受控端 Docker daemon 需配置 insecure-registries（10.13.66.25:5000）。
- **依赖外部**：内网仓库 10.13.66.25:5000（push/pull 与 /v2 API 检查同端口）必须可达；受控端主机需能直连该仓库。
