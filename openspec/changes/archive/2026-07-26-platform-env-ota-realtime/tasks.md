## 1. 数据库迁移（Flyway）

- [x] 1.1 `ticket` 表增 `contact VARCHAR(100)` 列（nullable）
- [x] 1.2 新建 `announcement_group` 关联表（`announcement_id BIGINT`, `group_id BIGINT`, 联合唯一索引），并回填：将既有 `announcement.target_scope='GROUP' AND target_id IS NOT NULL` 的 `target_id` 写入关联表
- [x] 1.3 新建 `env_file` 元数据表（`id`, `filename`, `md5`, `size`, `uploaded_at`, `uploaded_by`）
- [x] 1.4 新建 `agent_upgrade_task` 表（`id`, `instance_id`, `version`, `md5`, `status`, `created_at`, `finished_at`, `error`）用于持久化升级任务记录
- [x] 1.5 验证迁移可正向执行且幂等（重复执行不报错）

## 2. 受控端版本号注入（D1）

- [x] 2.1 新增 `internal/version/version.go`，声明 `var Version = "0.1.0-dev"`
- [x] 2.2 `internal/heartbeat/heartbeat.go:121` 将硬编码 `"agentVersion": "0.1.0"` 改读 `version.Version`
- [x] 2.3 `Makefile` `LDFLAGS` 改为 `-X github.com/nexcompute/controlled-agent/internal/version.Version=$(VERSION)`（替换既有 `-X main.version=...`）
- [x] 2.4 `build.ps1` Agent 构建段经 ldflags 注入 VERSION（优先 git tag，无 tag 用 Makefile `VERSION`）
- [x] 2.5 验证构建后受控端心跳上报的 `agentVersion` 与 VERSION 一致、管理端 `PhysicalInstance.agentVersion` 落库

## 3. 受控端环境准备 GUI（D2 / D3 / D5 / D6）

- [x] 3.1 `internal/gui/window.go` `buildWindow` 新增"受控端环境准备" Card，含 9 个按钮（按 `docs/受控端环境准备.md` 9 章一一对应，不合并；见 design D2）
- [x] 3.2 `internal/executil/executil_windows.go`（或新增辅助）实现 `StartElevatedPowerShell(script string) error`：经 `ShellExecute` verb `runas` 拉起 `powershell.exe -NoExit -NoProfile -Command <script>`，多行脚本写临时 `.ps1` 后 `-NoExit -File`
- [x] 3.3 9 个按钮中可直接执行的（安装 WSL / 检查显卡驱动 / 验证 Toolkit / 创建 GPU 容器 / 环境测试 / GPU 压力测试）调用 `StartElevatedPowerShell` 内置对应脚本，`-NoExit` 保证窗口不关闭
- [x] 3.4 "安装 Docker" 按钮调用 `StartElevatedPowerShell` 执行 Env 文件夹下 `Docker Desktop Installer.exe`（路径取 `${storageRoot}/Env/Docker Desktop Installer.exe`，缺失则提示先同步）
- [x] 3.5 新增 GUI 辅助 `showGuideDialog(title, codeBlock, hint string)`（Fyne `dialog.ShowCustom` + `clipboard.SetContent` 一键复制按钮）
- [x] 3.6 "离线安装 NVIDIA Container Toolkit" 按钮调 `showGuideDialog`：展示 4 条 `sudo dpkg -i ...` 命令 + 说明 deb 包在 Env 文件夹需复制到 WSL（design D5）
- [x] 3.7 "修改 Docker Engine 配置" 按钮先 `executil` 启动 Docker Desktop（多路径探测 `C:\Program Files\Docker\Docker\Docker Desktop.exe`），再 `showGuideDialog` 展示 JSON 配置 + 操作指引（design D6）
- [x] 3.8 验证各按钮拉起管理员 PowerShell、`-NoExit` 保持窗口、引导弹窗一键复制可用

## 4. Env 文件 MD5 同步（D4）

- [x] 4.1 后端 `EnvFileController`（`/api/admin/env-files` 上传/列表/删除，JWT + `@RequirePermission`）+ `EnvFileService`：存至 `${storage.root}/env/`，记 `env_file` 表 MD5
- [x] 4.2 后端 `GET /api/agent/env/list`（`/agent/**` SecurityConfig `permitAll` + WebConfig 排除，校验 agent token/instanceNumber，返回 `[{filename, md5, size}]`）
- [x] 4.3 后端"环境网盘同步"按钮：`POST /api/admin/env-files/sync`（管理员触发），经 WS 向在线受控端广播/定向下发 `env.sync` 命令；离线实例下次心跳后同步
- [x] 4.4 受控端 `internal/agent/env_syncer.go`（仿 `public_image_syncer.go`）：拉 `/api/agent/env/list`，对比本地 `${storageRoot}/Env` 文件 MD5，缺失/不一致者经 `/agent/file/**` 下载并校验
- [x] 4.5 受控端 `executor.go` `dispatch` 新增 `env.sync` 分支调用 `env_syncer.RunNow()`；启动时与定时同步（间隔复用 `PublicImageSyncInterval` 或新增配置）
- [x] 4.6 受控端 `config.go` 派生 `EnvDir = filepath.Join(StorageRoot, "Env")`，启动时 `os.MkdirAll`
- [x] 4.7 验证管理员上传后受控端按 MD5 增量同步至 `Env`、手动"环境网盘同步"立即触发、已存在且 MD5 一致不重复下载

## 5. 受控端 OTA 升级（D7）

- [x] 5.1 后端 `AgentOtaController`（`/api/admin/agent-upgrade`）：`upload` 上传新版 exe 存 `${storage.root}/agent-upgrade/{version}.exe` 记 MD5；`POST /upgrade` 按 `instanceIds` 批量/单独下发 `agent.upgrade`；`GET /tasks` 查升级任务状态
- [x] 5.2 后端 `AgentOtaService`：串行向各实例下发 `agent.upgrade`（payload `{version, md5, downloadUrl}`），单实例失败不阻断其他，写 `agent_upgrade_task` 状态
- [x] 5.3 受控端 `internal/agent/upgrade_handlers.go` 实现 `handleUpgrade`：经 `/agent/file/**` 下载至 `os.TempDir()\nexcompute-agent.new.exe`，MD5 校验失败回传失败不替换
- [x] 5.4 `handleUpgrade` 校验通过后备份当前 exe 至 `nexcompute-agent.exe.bak`，写 `updater.bat`（`timeout 2` -> `move /Y new.exe agent.exe` -> `start "" agent.exe` -> `del updater.bat`），`cmd /c start "" updater.bat`，`app.Quit()` 优雅退出
- [x] 5.5 `updater.bat` 失败回滚：`move` 失败则 `move agent.exe.bak agent.exe` 再 `start`
- [x] 5.6 受控端 `executor.go` `dispatch` 新增 `agent.upgrade` 分支
- [x] 5.7 验证升级保留 `nexcompute-agent.json`/`Env`/存储池根目录、升级期间进程不崩溃、升级后新版自动启动并恢复心跳、`.bak` 保留、MD5 不符不替换

## 6. 网页端右下角实时 Toast（D8）

- [x] 6.1 后端 `SseService` 复用 `pushToUser`；在产生未读消息处（容器变动 `ContainerService`、工单变动 `TicketService`、存储池变动 `StoragePoolService`）同时推送精简事件 `container.changed`/`ticket.changed`/`storage.changed`（payload `{type,title,message,link}`）
- [x] 6.2 前端 `src/utils/sse.ts` 增加对新事件类型的监听分发
- [x] 6.3 前端新增 `src/components/RealtimeToast.vue`（AntD `notification`/`message` 右下角堆叠、5s 自动消失、点击跳转 `link`），挂载于 `src/layouts/BasicLayout.vue`
- [x] 6.4 同事件去抖/限频，避免短时风暴
- [x] 6.5 验证容器/工单变动后网页端右下角实时弹出 Toast、自动消失、与未读消息收件箱并存

## 7. 登录公告中央弹窗（D9）

- [x] 7.1 后端 `AnnouncementController` 新增 `GET /api/announcements/login-banner`：返回定向当前用户且未读的公告列表
- [x] 7.2 前端新增 `src/components/AnnouncementLoginModal.vue`（`a-modal` 屏幕中央），挂载于 `BasicLayout.vue`，登录后（角色 MENTOR/ADMIN）调用接口
- [x] 7.3 "已读"按钮：标记该公告已读并展示下一条；"下次再说"按钮：`localStorage` 记 `dismissedAnnouncements` + 本会话不再弹
- [x] 7.4 验证导师/管理员登录后中央弹窗、已读/下次再说行为正确

## 8. 公告多选课题组（D10）

- [x] 8.1 `Announcement` 领域：`targetScope=GROUP` 时由单值 `targetId` 改为多对多 `announcement_group`（`@ManyToMany` 或显式关联表映射）
- [x] 8.2 `AnnouncementService` 发布/可见性判定按组集合匹配用户课题组（替换 `targetId` 单值匹配）
- [x] 8.3 `AnnouncementController` 创建/更新接口接收 `targetGroupIds: Long[]`
- [x] 8.4 前端 `AnnouncementManageView.vue` `GROUP` 分支 `a-select mode="multiple"`（替换单选）；列表展示多课题组名
- [x] 8.5 `api/announcement.ts` 扩展 `targetGroupIds`
- [x] 8.6 验证多选课题组公告仅对所选课题组成员可见、旧单选数据迁移后仍可见

## 9. 工单详情 + 联系方式（D11）

- [x] 9.1 `Ticket` 领域增 `contact` 字段；`TicketCreateRequest` 增 `contact`；`TicketService.submit` 持久化
- [x] 9.2 前端 `TicketListView.vue` 提交表单增"联系方式"字段，默认填账户手机号（`auth` store 用户信息 `phone`），可改
- [x] 9.3 `TicketController`/`TicketService` 新增"详情"返回按状态分组的工单（或前端按 `status` 分两组渲染）
- [x] 9.4 前端 `TicketManageView.vue` 新增"详情"抽屉/页，含"已处理"（CLOSED）与"待处理"（PENDING）两张表格
- [x] 9.5 验证联系方式默认手机号可改、详情同屏两表分组正确

## 10. 系统信息展示（D12）

- [x] 10.1 前端 `SystemInfoView.vue` 移除 `<a-alert v-else "仅管理员可修改系统信息">` 分支
- [x] 10.2 导师/学生字段以只读 `disabled` 展示，仅管理员（`isOwner`/ADMIN）可编辑
- [x] 10.3 验证导师/学生进入直接显示系统信息无提示、仅管理员可编辑

## 11. 权限矩阵恢复默认 + 修改确认（D13）

- [x] 11.1 后端 `PermissionController` 新增 `POST /api/admin/permissions/reset-default`；`PermissionService.resetToDefault` 从平台默认矩阵种子恢复
- [x] 11.2 后端恢复默认与修改均记审计日志
- [x] 11.3 前端 `PermissionMatrixView.vue` 保存改为弹 `a-modal` 确认（"确认修改权限矩阵？"）确认方提交
- [x] 11.4 前端新增"恢复默认"按钮（`a-popconfirm` 二次确认）
- [x] 11.5 `api/permission.ts` 新增 `resetDefault`
- [x] 11.6 验证修改需确认生效、恢复默认经二次确认后恢复、操作记审计

## 12. 硬件指纹强化（D14）

- [x] 12.1 `internal/sysinfo/sysinfo.go` `CollectMachineFingerprint` 增 SMBIOS UUID 采集（`wmic csproduct get UUID` 经 `executil` 或 Go SMBIOS 库），作为主指纹；MAC/MachineGuid 保留为辅助
- [x] 12.2 `MachineFingerprint` 结构增 `SmbiosUUID` 字段；心跳上报携带
- [x] 12.3 后端 `HeartbeatRequest`/`HeartbeatService` 增 `smbiosUUID`；去重匹配优先 SMBIOS UUID，空/全 0 回退 MachineGuid 再回退 MAC（`findByMacAndMachineCode` 扩展为按 UUID 优先查）
- [x] 12.4 `PhysicalInstance` 增 `smbios_uuid` 列（Flyway）与字段
- [x] 12.5 验证换网卡（MAC 变）但主板/系统不变时复用既有编号不重复注册、UUID 缺失时回退不阻断注册

## 13. 物理实例版本展示

- [x] 13.1 后端 `PhysicalInstanceController` 列表 DTO 暴露 `agentVersion`（实体已含列，DTO 补字段）
- [x] 13.2 前端 `InstanceManageView.vue`/`InstanceStatusView.vue` 表格增"受控端版本"列
- [x] 13.3 验证在线与离线实例均展示版本（离线展示最后心跳版本）

## 14. 端到端验证

- [x] 14.1 受控端环境准备：8 按钮 PowerShell 提权运行、窗口保持、引导弹窗一键复制、Env 文件先同步后安装 Docker
- [x] 14.2 OTA：管理员上传新版 -> 批量/单独升级 -> 受控端保留配置不崩溃自启 -> 管理端版本刷新
- [x] 14.3 实时响应：容器/工单变动右下角 Toast、登录公告中央弹窗、公告多选课题组
- [x] 14.4 工单：详情已处理/待处理两表、联系方式默认手机号
- [x] 14.5 权限矩阵恢复默认 + 确认、系统信息导师/学生直接展示
- [x] 14.6 硬件指纹：换网卡不重复注册、UUID 缺失回退
- [x] 14.7 `openspec validate platform-env-ota-realtime --strict` 通过
