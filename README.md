# Nexcompute（合算）分布式 GPU 算力管理平台

把分布式 Windows + GPU 主机统一为一个平台，按课题组角色分配机器、远程管理 Docker 容器、隔离用户存储、复用镜像。

## 架构

```
┌─────────────────────────────────────────────────┐
│              管理端（单一实例）                     │
│  ┌──────────────┐  ┌──────────────┐            │
│  │ SpringBoot   │  │  Vue + AntD  │            │
│  │   后端 API    │  │  + ApexCharts│            │
│  └──────┬───────┘  └──────────────┘            │
│         │ PostgreSQL + Redis                    │
│  ┌──────┴───────────────────────────────┐      │
│  │ HTTP 心跳 / WS 命令通道 / SSE 推送    │      │
│  └──────────────────────────────────────┘      │
└────────────────┬────────────────────────────────┘
                 │ 全出站连接
    ┌────────────┼────────────┐
    ▼            ▼            ▼
┌───────┐  ┌───────┐   ┌───────┐
│受控端 A│  │受控端 B│   │受控端 C│  (Go exe + GUI)
│Win+GPU│  │Win+GPU│   │Win+GPU│
│Docker │  │Docker │   │Docker │
└───────┘  └───────┘   └───────┘
```

## 目录结构

```
Nexcompute/
├── management-backend/      # SpringBoot 后端（Gradle）
├── management-frontend/     # Vue 3 + Ant Design + ApexCharts 前端
├── controlled-agent/        # Go 受控端（托盘 GUI + 心跳 + WS + Docker）
├── docker-compose.yml       # PostgreSQL + Redis
└── openspec/                # 规格与变更记录
```

## 技术栈

- **管理端后端**: SpringBoot 3.5 + JPA + Flyway + Spring Security + JWT + WebSocket + SSE
- **管理端前端**: Vue 3 + TypeScript + Vite + Ant Design Vue + ApexCharts + Pinia
- **受控端**: Go + Fyne(GUI) + systray + gorilla/websocket + Docker SDK
- **基础设施**: PostgreSQL 16 + Redis 7（Docker Compose）

## 快速开始

### 1. 启动基础设施

```bash
docker-compose up -d
```

### 2. 启动管理端后端

```bash
cd management-backend
./gradlew bootRun
```
默认管理员：`admin` / `admin123`（首次登录后应修改）

### 3. 启动管理端前端

```bash
cd management-frontend
npm install
npm run dev
```
访问 http://localhost:5173

### 4. 构建并运行受控端

```bash
cd controlled-agent
# 需要 Go 1.23+ 与 CGO（Fyne 依赖）
CGO_ENABLED=1 go build -o nexcompute-agent.exe ./cmd/nexcompute-agent
./nexcompute-agent.exe
```

## 三角色体系

| 角色 | 权限 |
|------|------|
| 管理员 | 全部模块全部操作（物理实例管理、用户管理、权限矩阵、PowerShell 等） |
| 导师 | 学生模块 + 课题组管理 + 学生资源分配 |
| 学生 | 物理实例状态、容器、镜像、存储池、工单、公告 |

权限矩阵（角色 × 模块 × 操作）可由管理员细粒度配置。

## 核心能力

- **受控端通信**：心跳保活（HTTP）+ 独立 WS 命令通道，全出站穿透 NAT
- **物理实例管理**：自动注册编号、状态监控、远程重启/息屏/PowerShell
- **容器生命周期**：表单创建（SSH 密码/资源限制/端口映射）、启停重启删、容器级 SSH 密码即时重置
- **存储池**：项目级命名隔离、共享授权、跨机迁移（断点续传）
- **镜像管理**：容器 commit 为 tar、归属与可见性、公共镜像库自动同步
- **监控**：SSE 实时推送 + 30 日历史趋势 + 按角色进程查看
- **工单系统**：五种类型、管理员回复关闭、通知提交学生
- **公告与通知**：定向/定时公告、未读消息收件箱（永久保留）、SSE 实时推送

详见 `openspec/changes/build-nexcompute-platform/`。

## 测试

```bash
# 后端单元测试
cd management-backend && ./gradlew test

# 受控端 Go 测试
cd controlled-agent && go test ./...

# 端到端联调：见 openspec/changes/build-nexcompute-platform/e2e-tests.md
```

## 存储与镜像 tar 路径（platform-improvements item 5）

镜像以 tar 文件形式在管理端存储与分发（D3 tar 方案，无镜像 pull）。存储位置由 `nexcompute.storage.*`（`application.yml` / `NexcomputeProperties` / `StorageInitializer`）配置，根目录默认 `./data/storage`，可用 `STORAGE_ROOT` 环境变量覆盖：

- **私有镜像**：`${image-tar-dir}/{userId}/{name}-{tag}.tar`（默认 `./data/storage/images/{userId}/{name}-{tag}.tar`；`upload-tar` 接口为避免重名使用随机 UUID 文件名，元数据 `tar_path` 记录实际路径）。
- **公共镜像**：`${public-image-dir}/{name}-{tag}.tar`（默认 `./data/storage/public-images/{name}-{tag}.tar`）。
- **受控端临时文件**：受控端接收的 tar 落在 `os.TempDir()`，`docker load` 后即删，不持久化。

容器创建时，管理端创建容器前先下发 `image.load`：受控端检查本地是否已持有该镜像（已持有则跳过），否则经 file-transfer 下载 tar 并 `docker load`，成功后再创建容器（复用受控端既有 `image.load` 命令处理，无需新增表或受控端命令）。详见 [design.md](openspec/changes/platform-improvements/design.md) D8/D12。
