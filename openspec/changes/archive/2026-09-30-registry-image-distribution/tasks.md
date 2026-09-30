## 1. 前置验证与配置

- [x] 1.1 验证 10.13.66.25:5000 暴露 Registry v2 API（`curl http://10.13.66.25:5000/v2/_catalog?n=5` 与 `HEAD /v2/<repo>/manifests/<tag>`），确认返回结构（分页 Link 头、404/错误码形态）；结论记录到 design.md 风险节
- [x] 1.2 `application.yml` 增 `nexcompute.registry.url`（默认 10.13.66.25:5000；docker 引用前缀与 `http://{url}/v2/` API 基址同源派生），`NexcomputeProperties` 加对应字段；两个 compose example 模板加占位；启动后能读到配置（日志打印一次）

## 2. 后端数据模型与 RegistryClient

- [x] 2.1 Flyway V35：`image_metadata` 加 `distribution VARCHAR(20) NOT NULL DEFAULT 'TAR'`、`registry_valid BOOLEAN NULL`、`registry_checked_at TIMESTAMPTZ NULL`（COMMENT ON 中文注释，SQL 内不出现 `${`）；`ImageMetadata` 实体同步加字段；启动 `validate` 通过
- [x] 2.2 新建 `RegistryClient`：`exists(repo, tag)`（HEAD manifest）、`catalog()`（_catalog 分页 Link next）、`tags(repo)`；连接异常抛 BusinessException 而非返回不存在；单元测试覆盖 200/404/异常三分支（MockWebServer 或接口桩）

## 3. 后端镜像登记/有效性/无标记镜像

- [x] 3.1 `ImageService.registerRegistryImage`（重名校验：任何既有记录 name:tag 相同即拒绝；status=UPLOADING、distribution=REGISTRY）与 `refreshValidity`（exists -> 更新 registry_valid/checked_at，true 且 UPLOADING -> READY）；`ImageController` 增 `POST /images/register`、`POST /images/{id}/refresh-validity`、`GET /images/{id}/push-commands`（返回 registryUrl+tagCmd+pushCmd）；单元测试覆盖登记、重名拒绝、刷新有效/无效/仓库不可达
- [x] 3.2 无标记镜像：`GET /images/registry/untagged`（catalog×tags − 系统记录，管理员权限）与 `POST /images/registry/untagged/claim`（建记录 READY+registry_valid=true+sourceContainer=registry-claim，visibility 默认 SHARED_TO_ALL）；非管理员访问返回无权限；单元测试覆盖过滤与补录
- [x] 3.3 `resolveVisibleImage`：REGISTRY 镜像要求 READY 且 registry_valid=true（TAR 镜像维持 READY）；测试覆盖无效仓库镜像不可选

## 4. 后端 commit 编排改 push 确认

- [x] 4.1 定位现有 commit 编排（`image.commit` 下发处）：payload 增 `registryUrl`；`registerCommitImage` 传 name=repo（`工号-项目-镜像名-标签-备注-随机串`）、tag=latest、distribution=REGISTRY；命令成功 -> READY+registry_valid=true，失败/超时 -> FAILED（沿用 REQUIRES_NEW 落盘经验，防 @Transactional 回滚吞状态）；测试模拟命令成功/失败验证状态落盘

## 5. 后端 image.pull 与进度 SSE

- [x] 5.1 泛化 progress 路由：`AgentWebSocketHandler` 的 progress 消息改为经按 commandId 的 `ProgressRouter`（注册/注销 listener），`OtaProgressTracker` 改为经 router 注册，OTA 进度行为不回归（现有 OTA 测试通过）
- [x] 5.2 `AgentCommandService` 增带 `ProgressListener` 的 `sendCommand` 重载（暴露 commandId 供注册）；`ProgressMessage` DTO 增 `Text` 字段
- [x] 5.3 `ensureImageLoaded` 分支：distribution=REGISTRY -> 发 `image.pull`（payload 含 `registryUrl/name:tag`），进度经 `SseService.pushToUser(userId, "containerImagePull", {imageRef, stage, percent, text})`；TAR -> 原 `image.load` 不变；单测覆盖分支选择与 SSE 事件触发
- [x] 5.4 受控端未知命令（旧版遇 `image.pull`）错误能透传到创建容器报错信息（含"受控端需升级"提示）

## 6. 受控端 agent（Go）

- [x] 6.1 单实例：main.go 早期创建 `Global\NexcomputeAgentSingleInstance` 命名互斥，ERROR_ALREADY_EXISTS -> 日志+退出；手测：启动两个实例仅存一个，正常退出与任务管理器强杀后均可再启动
- [x] 6.2 `docker/image.go` 增 `TagImage` 与 `PullImage`（返回进度流）；`image_handlers.go` 的 `handleImageCommit` 改为 commit -> tag(registryUrl/repo:latest) -> push（重试 ≤3，超时 30min），移除 save+tar 上传；repo 沿用 `buildCommitTarName`；`go test ./...` 通过
- [x] 6.3 新增 `handleImagePull`（image.pull）：ImageExists(registry ref) -> already_exists；否则 ImagePull 解析 JSON 进度流，节流回传 `SendProgress`（Stage=pulling、Percent、Text=层状态）；insecure HTTPS/HTTP 类错误返回友好中文提示；注册命令路由
- [x] 6.4 真机联调：升级 agent 后（1）commit 容器 -> 仓库出现 `10.13.66.25:5000/工号-...:latest`；（2）删除本地镜像后创建容器 -> pull 成功且进度回传；（3）重复启动 agent 仅存一进程
- [x] 6.5 环境准备卡片新增“9. 配置私有镜像仓库”按钮：复用 `showGuideDialog` 弹窗展示并一键复制 `,"insecure-registries": ["10.13.66.25:5000"]`（含前导逗号；hint 说明粘贴到既有 JSON 末项之后、空 `{}` 时去逗号）；同步更新 `docs/受控端环境准备.md`；手测复制到剪贴板内容逐字符正确

## 7. 前端

- [x] 7.1 `api/image.ts` 增 register/refreshValidity/pushCommands/untagged/claim 接口；`ImageListView.vue` 上传弹窗改登记表单（原始镜像名/原始标签/应用端口/容器内挂载/使用说明），移除 tar 上传入口；`npm run type-check` 通过
- [x] 7.2 操作列增"上传"（弹窗展示 tag/push 命令 + 复制按钮，数据来自 push-commands 接口）与"刷新状态"（调接口后行内更新）；列表新增"有效性"列（有效/无效/未检查，TAR 镜像显示"-"）
- [x] 7.3 管理员"无标记镜像" tab：列表（repo/tags/大小若有）+ "登记"补录表单（应用端口/挂载点/使用说明/可见性）调 claim；非管理员不显示入口
- [x] 7.4 容器创建：提交期间经 SSE 订阅 `containerImagePull`（按 imageRef 过滤）实时展示进度条+分层文本，随 POST 结果收敛；镜像下拉对无效仓库镜像禁用并标注
- [x] 7.5 端到端验证（dev 栈）：登记->推送->刷新有效->创建容器（进度实时）->commit 镜像出现在仓库与列表->无标记镜像补录可用；`npm run type-check` 与后端全量测试通过

## 8. 文档与收尾

- [x] 8.1 README/DEPLOY 增：受控端 daemon.json insecure-registries（10.13.66.25:5000）配置说明、新环境变量说明；更新 compose example 注释
- [x] 8.2 全量回归：后端 `gradle test`、前端 `type-check`、agent `go test ./...`；`openspec validate registry-image-distribution` 通过，归档准备就绪
