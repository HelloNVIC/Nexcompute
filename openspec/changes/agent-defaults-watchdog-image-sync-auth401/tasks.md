# Tasks

## 1. 受控端：存储池默认值与补填

- [x] 1.1 `internal/config/config.go`：`defaultCfg` 增加 `StorageRoot`（raw string `D:\lab404`）；`loadOrDefault` 在 Unmarshal 后判空补填默认值并 `persist`，补填路径 `MkdirAll`（失败仅记日志不阻断）。`config_test.go` 补三个用例：新装（无文件）得默认值、存量空值补填并落盘、已有非空值不被覆盖——`go test ./internal/config/` 全绿
- [x] 1.2 验证 `logging.Writer` 对不可写/不存在根目录的容错（D 盘缺失机器）：确认 `EnsureWriter` 失败时回退 `%LOCALAPPDATA%`，必要时加固——以单测或手动将 root 指向不存在盘符运行受控端验证
- [x] 1.3 `go test ./...` 全绿，`build.ps1 -Target Agent` 构建通过

## 2. 受控端：心跳重写修复与持久化加固

- [x] 2.1 `internal/heartbeat/heartbeat.go`：补推分支增加判等——回包密码与 `cfg.LocalAdminPassword` 相同则不调用 `config.Update`；调用处 `_ =` 改为记日志。补/改单测：密码相同不触发写盘（可用注入 osExecutablePath 的 config 单测配合验证文件 mtime/内容不变）
- [x] 2.2 `internal/config/config.go`：`persist` 改原子写（写同目录 `.tmp` 后 `os.Rename`）——`go test ./internal/config/` 现有 roundtrip 用例通过
- [ ] 2.3 手动验证：受控端运行中手动编辑 `nexcompute-agent.json`（如改 heartbeatInterval），等待 ≥5 秒确认文件内容不再被内存旧值覆盖

## 3. 受控端：Docker Desktop 看门狗 30 秒

- [x] 3.1 `internal/docker/desktop.go`：周期 10min→30s、删除 2 分钟首查延迟、新增 60s 拉起冷却（`lastStartAttempt` 判断）、失败日志限流（状态转换记一条 + 连续失败每 20 次记一条）；时间参数常量化。冷却判断逻辑抽纯函数并补单测——`go test ./internal/docker/` 通过
- [ ] 3.2 手动验证：正常退出 Docker Desktop 后 ≤30 秒被自动拉起；启动期观察日志无重复拉起、无错误刷屏

## 4. 后端：未认证 401 入口点

- [x] 4.1 `SecurityConfig.filterChain` 增加 `.exceptionHandling(...authenticationEntryPoint)`：写 HTTP 401 + `ApiResponse.error(TOKEN_INVALID)`（注入 ObjectMapper，响应形状与 GlobalExceptionHandler 一致）。相关既有测试经 Gradle 全绿，新增/调整用例断言 entryPoint 响应体含 code=1003
- [ ] 4.2 手动验证：无 token 与过期 token curl 受保护端点均 401+1003；`/api/agent/heartbeat`、`/api/auth/login` 等 permitAll 路径行为不变

## 5. 后端：V36 迁移与实体

- [ ] 5.1 `V36__image_sync_tables.sql`：`image_sync_batch` 与 `image_sync_task` 两表（列见 design #4），`COMMENT ON` 中文注释，全文无 `${`。启动后端确认 Flyway 应用成功且 `ddl-auto=validate` 不报错
- [x] 5.2 JPA 实体（`ImageSyncBatch`/`ImageSyncTask`）与 Repository，字段/类型与 DDL 精确一致——Gradle 编译与既有测试通过

## 6. 后端：镜像同步服务、API 与 SSE

- [x] 6.1 `ImageSyncService`：批次创建（REGISTRY+registryValid 校验、RUNNING 批次拒绝）、实例全集二分（在线/离线）、离线任务即刻 OFFLINE_SKIPPED 并推 SSE、在线任务提交固定线程池（16）执行五参 `sendCommand("image.pull", imageRef, 600_000, listener)`、listener 节流（≥1s 或状态变化）更新任务行 + `pushToUser(adminId, "imageSyncProgress", …)`、结果→终态映射（pulled/already_exists/null 超时/error 失败）、全部终态后批次 DONE。Mockito 单测参照 `ContainerServiceImagePullTest` 覆盖：离线跳过、already_exists、超时、失败落 error、重复发起拒绝、非管理员拒绝——Gradle 对应测试类全绿
- [x] 6.2 `ImageController` 三端点：`POST /images/{id}/sync-all`、`GET /images/sync-batches/{batchId}`、`GET /images/{id}/sync-batches/active`，`@RequirePermission(module="image", action=EDIT)`（沿用镜像模块名单数）+ 服务内管理员校验（对齐无标记镜像入口做法）——单测或手动以权限矩阵低权限账号验证 403
- [x] 6.3 SSE 事件完整性验证：建批初始态、每次状态变化、终态均推 `imageSyncProgress` 帧（`{batchId, instanceNumber, status, percent, text, error}`），`already_exists` 秒回场景无进度帧但有终态帧——与 8.4 联调一并确认

## 7. 前端：登录失效提示加固

- [x] 7.1 `utils/request.ts`：模块级 `redirecting` 标志（401/1003 首次弹「登录已失效，请重新登录」+ logout + 跳转，并发静默 reject）；HTTP 200+code 1003/401 分支文案同步改为「登录已失效，请重新登录」；裸 403 分支保留防御提示不登出——`npm run type-check` 通过
- [ ] 7.2 手动验证：篡改 localStorage token 触发失效，多请求并发页面仅弹一次提示并跳转登录页

## 8. 前端：镜像同步视图

- [x] 8.1 `api/image.ts`：`syncAll`/`getSyncBatch`/`getActiveSyncBatch` 及类型定义——`npm run type-check` 通过
- [x] 8.2 `ImageListView`：REGISTRY 且 `registryValid` 且 admin 显示「同步到所有机器」，点击先 `Modal.confirm`，再经 active 探测决定恢复进行中批次或发起新批次——type-check 通过
- [x] 8.3 同步抽屉：批次头（镜像引用 + 成功/失败/跳过计数）+ 实例表格（状态 Tag、进度条、层文本 tooltip、错误列）；SSE 订阅 `imageSyncProgress` 按 `instanceNumber` 更新行——type-check 通过
- [ ] 8.4 端到端联调：多机同时拉取看实时进度、已存在秒完成、离线标跳过、人为断网/错误仓库地址验证失败与超时终态、刷新页面经 active 接口恢复、进行中重复发起被拒——全部按 spec 场景逐条核验

## 9. 收尾

- [x] 9.1 三端构建全绿：`go test ./...`、Gradle `test`、`npm run build`（含 vue-tsc）
- [x] 9.2 README 同步：镜像"同步到所有机器"操作说明、V36 表、登录失效行为（401+1003）描述更新
