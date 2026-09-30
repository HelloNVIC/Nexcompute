## Context

现状：镜像 tar 存管理端（`imageTarDir`），创建容器时 `ContainerService.ensureImageLoaded` 同步下发 `image.load`（file-transfer 下载 + `docker load`，600s 超时，无进度反馈）；容器提交走 `docker commit` + `docker save`（tar 名 `工号-项目-镜像名-标签-备注-随机串`）+ file-transfer 回传。受控端已有 WS progress 消息机制（`ProgressMessage`，OTA 专用，后端 `AgentWebSocketHandler` 硬路由到 `OtaProgressTracker`），前端已有 SSE 基础设施（`SseService.pushToUser` + `utils/sse.ts`）。受控端无单实例防护。内网仓库：10.13.66.25:5000，docker push/pull 与 Registry v2 API 同端口同服务（如 http://10.13.66.25:5000/v2/_catalog）。用户已确认：commit 镜像 repo=原 tar 全名（`工号-项目-镜像名-标签-备注-随机串`）、tag 固定 `latest`；系统记录 name=仓库 repo 名。

## Goals / Non-Goals

**Goals:**
- 仓库镜像：登记（无文件）→ 用户本机 push → 有效性检查 → 可用于创建容器（pull 分发）。
- commit 镜像改 push 到仓库，沿用原 tar 命名（repo=全名，tag=latest）。
- 创建容器时经既有 progress 通道 + SSE 实时展示 pull 进度。
- 无标记镜像发现（仓库有、系统无记录）与补录登记。
- 受控端单实例（互斥标记）。
- 存量 tar 镜像按原路径继续分发（兼容过渡）。

**Non-Goals:**
- 不做仓库端镜像删除/GC；不引入仓库认证（内网 insecure HTTP）。
- 不自动改造存量 tar 镜像为仓库镜像；不做定时全量有效性巡检（仅手动刷新 + 关键时点检查）。
- 不自动配置受控端 Docker daemon 的 insecure-registries（运维文档说明，agent 仅对典型错误给出友好提示）。

## Decisions

### D1 配置：单一仓库地址，走 application.yml + compose 模板

`application.yml` 新增（遵循 `${ENV:default}` 惯例，同步两个 compose example 模板）：
- `nexcompute.registry.url`：私有仓库地址，默认 `10.13.66.25:5000`。docker 引用前缀（push/pull、推送命令展示）与 v2 API 检查基址（`http://{url}/v2/...`）同源派生。

理由：v2 API 与 push/pull 同端口同服务（用户确认 catalog 即 `10.13.66.25:5000/v2/_catalog`），单配置项即可，管理端经 insecure HTTP 直连。备选（独立 api-url 配置项）被否--最初误以为检查走 5002，已无必要。

### D2 数据模型：`image_metadata` 增量列（V35）

- `distribution VARCHAR(20) NOT NULL DEFAULT 'TAR'`：`TAR`（存量）/ `REGISTRY`（仓库镜像）。
- `registry_valid BOOLEAN NULL`：null=未检查（tar 镜像恒 null），true/false 为检查结论。
- `registry_checked_at TIMESTAMPTZ NULL`。
- 状态语义不变（UPLOADING/READY/FAILED）：登记未推送与 commit 推送中均=UPLOADING；登记镜像刷新确认存在→READY 且 registry_valid=true；commit push 成功→READY 且 registry_valid=true（受控端已确认）。
- 可用性规则：`resolveVisibleImage` 对 `distribution=REGISTRY` 要求 `READY && registry_valid=true`，TAR 镜像维持仅 READY。前端下拉对无效仓库镜像禁用并标注。

### D3 后端 RegistryClient + 接口

新 `RegistryClient`（Java HttpClient，直连 `http://{nexcompute.registry.url}/v2`，管理端进程内调用）：- `exists(repo, tag)`：`HEAD /v2/{repo}/manifests/{tag}`（带 Accept 头）。200/404 → 存在/不存在；连接异常 → 抛错（不误标无效，前端提示检查失败）。
- `catalog()`：`GET /v2/_catalog?n=1000` + Link 分页；`tags(repo)`：`GET /v2/{repo}/tags/list`。

`ImageController` 新增：
- `POST /images/register`：登记 {name, tag, appPorts, mountPoint, usageInstructions}；`distribution=REGISTRY`、`status=UPLOADING`；**重名校验：任何既有记录 name:tag 相同即拒绝**（仓库类为 spec 要求，含 TAR 类是为避免 `resolveVisibleImage` 引用歧义）。
- `GET /images/{id}/push-commands`：返回 `{registryUrl, tagCmd, pushCmd}`（命令由后端拼装，前端不硬编码仓库地址）。
- `POST /images/{id}/refresh-validity`：调 exists 更新 registry_valid/checked_at；true 且原为 UPLOADING → 置 READY。
- `GET /images/registry/untagged`（仅管理员）：catalog×tags − 系统记录（按 name:tag 匹配），返回 {repo, tags[]}。
- `POST /images/registry/untagged/claim`（仅管理员）：{repo, tag, appPorts, mountPoint, usageInstructions, visibility} → 建记录（owner=当前管理员，`distribution=REGISTRY`、READY、registry_valid=true、sourceContainer=`registry-claim`，visibility 默认 SHARED_TO_ALL）。

### D4 commit 改 push（agent `image_handlers.go`）

`image.commit` payload 增加 `registryUrl`（管理端配置下发，agent 不加配置项）。流程：`docker commit`（本地 ref=`{repo}:{tag}`，repo=原 `buildCommitTarName` 全名，tag=latest）→ `ImageTag` 到 `{registryUrl}/{repo}:latest` → `ImagePush`（insecure HTTP，无认证）。超时 10min→30min；push 失败重试 ≤3 次（对齐 build.ps1 对 insecure registry EOF 重试的经验）。成功返回 `{repo, tag, imageRef, sizeBytes}`。管理端编排：`registerCommitImage`（UPLOADING，name=repo、tag=latest）→ 命令成功 → READY+registry_valid=true；失败 → FAILED（沿用现有落盘/回调语义）。`docker save` 与 tar 回传路径从 commit 中移除；`ImageTransferEvent` 监听保留（存量公共镜像同步等仍用 file-transfer）。

### D5 分发改 pull：`image.pull` 命令 + 进度回传

`ensureImageLoaded` 分支：
- `distribution=REGISTRY` → 新命令 `image.pull`，payload `{imageRef: registryUrl/name:tag}`，超时 600s 不变。
- TAR（含 tarPath 空）→ 原 `image.load` 不动。

agent `image.pull`：`ImageExists`（完整 registry ref）→ 已有返回 `already_exists`；否则 `cli.ImagePull` 读 JSON 进度流（`ID/Status/ProgressDetail`），逐条节流（≥200ms 或状态变化）经 `MessageSender.SendProgress` 回传：`Stage="pulling"`、`Percent`（按各层 ProgressDetail 字节估算）、新增 `Text` 字段（如 `a1b2c3: Downloading 45%` / `Extracting` / `Pull complete`）。`ProgressMessage`（agent + 后端 DTO）加 `Text` 字段，OTA 不受影响（字段可缺省）。

### D6 progress 路由泛化 + SSE 推送（实时进度）

`AgentWebSocketHandler` 中 progress 消息目前硬路由 `OtaProgressTracker`。新增按 `commandId` 的通用 `ProgressRouter`（注册/注销 listener）：OTA tracker 对升级命令注册 listener（行为不变）；`AgentCommandService` 提供 `sendCommand(..., ProgressListener)` 重载，`ensureImageLoaded` 用它把 image.pull 进度经 `SseService.pushToUser(userId, "containerImagePull", {imageRef, stage, percent, text})` 推给创建用户。`POST /containers` 保持同步语义不变（进度是旁路推送，丢帧不影响结果）。备选（前端轮询任务表，仿 OTA tasks）被否：需求强调实时，且 SSE 设施现成。关联性：事件带 imageRef，前端按当前创建表单所选 imageRef 匹配。

### D7 前端改动

- `ImageListView.vue`：上传弹窗改登记表单（原始镜像名/原始标签/应用端口/容器内挂载/使用说明，无文件）；操作列增"上传"（命令弹窗+复制按钮，调 push-commands）与"刷新状态"；列"有效性"（有效/无效/未检查，tar 镜像显示"—"）；管理员新增"无标记镜像" tab（列表 + 补录表单）。tar 上传入口移除（`parse-tar`/`upload-tar` 后端保留不再使用）。
- 容器创建：提交期间订阅 SSE `containerImagePull`，按 imageRef 匹配展示进度条 + 分层文本；完成/失败随 POST 结果收敛。

### D8 受控端单实例（main.go）

启动最早期（logging 初始化后、GUI/托盘/WS/心跳前）创建命名互斥 `Global\NexcomputeAgentSingleInstance`（`golang.org/x/sys/windows` CreateMutex）：`ERROR_ALREADY_EXISTS` → 记日志"检测到已有受控端实例，本进程自动退出"并 `os.Exit(0)`；否则持有至进程结束（OS 在进程退出/崩溃时自动回收句柄，异常退出不残留）。备选（进程名扫描）被否：易误判（同名/改名）、无法可靠区分僵死进程。跨会话用 `Global\` 命名空间（RDP 多会话也唯一）。

### D9 受控端"配置私有镜像仓库"引导按钮（gui/envprep.go）

环境准备卡片（`buildEnvPrepCard`）末尾追加按钮"9. 配置私有镜像仓库"，复用既有 `showGuideDialog(title, codeBlock, hint)` 弹窗（自带"一键复制"按钮）。展示与复制内容为 `,"insecure-registries": ["10.13.66.25:5000"]`--**含前导逗号**（Docker Desktop > Settings > Docker Engine 的既有 JSON 通常已有末项，追加粘贴即合法）；hint 说明粘贴位置，并提示 daemon.json 为空对象 `{}` 时需去掉前导逗号。仓库地址为部署固定内网地址，与 envprep.go 既有硬编码部署常量（Toolkit 包名等）同策略：定义为 agent 侧常量（若后端 `registry.url` 改配，需同步此常量）。备选（agent 配置项下发）被否：单固定仓库场景无必要。同步更新 `docs/受控端环境准备.md` 新章节。

## Risks / Trade-offs

- [仓库 API 实际返回结构与预期不符（分页 Link 头/字段名差异）] -> 实施首步 curl 实测 `_catalog` 与 manifest HEAD 的返回，RegistryClient 按实测适配（任务 1.1）。**实测结论（2026-08-23）**：`GET /v2/_catalog?n=5` -> 200 `{"repositories":[...]}`（单页无 Link 头，超页时按标准 Link rel="next" 翻页）；`GET /v2/{repo}/tags/list` -> 200 `{"name":repo,"tags":[...]}`；`HEAD /v2/{repo}/manifests/{tag}` 存在 -> 200（Content-Type 为 OCI index `application/vnd.oci.image.index.v1+json`，Accept 头需含 OCI 类型或省略让服务端默认返回），tag 或 repo 不存在 -> 404 + JSON 错误体。结构符合标准 Registry v2，按设计实现即可。
- [insecure HTTP registry push 偶发 EOF（.18:5002 前车之鉴）] → agent push 重试 ≤3；commit 失败可重试（FAILED 状态可重新提交）。
- [受控端 daemon 未配 insecure-registries -> pull 失败] -> 受控端环境准备新增「配置私有镜像仓库」引导按钮（弹窗展示 + 一键复制追加内容，见 D9）；README/DEPLOY 文档同步说明；agent 对 server gave HTTP response to HTTPS client 类错误回传友好中文提示。
- [SSE 丢帧/断连] → 进度仅为展示旁路，最终结果仍由 POST /containers 同步返回，无损正确性。
- [catalog 分页遗漏大量镜像] → 按 Link rel="next" 翻页，单页 n=1000。
- [同用户并发创建容器时 SSE 事件串扰] → 事件携带 imageRef 过滤；极端并发下进度展示可能交错（可接受，结果正确）。

## Migration Plan

1. 部署前置：确认 .25 仓库可达且 daemon 配置 insecure-registries；升级后端（V35 自动迁移，纯加列带默认值，旧代码可回滚兼容）。
2. 受控端随 OTA 发新版（含单实例 + pull/push），旧版 agent 仍可用（image.load 兼容存量 tar 镜像；新 `image.pull` 命令旧 agent 不识别会报错——故创建容器选新仓库镜像需 agent 已升级，前端/后端对未知命令错误透传提示"受控端需升级"）。
3. 回滚：后端回退到上一镜像即可（新增列不破坏旧实体 validate——需保证旧实体类无新列映射即可，本变更只加列不改旧列）。agent 单独回退无影响。
