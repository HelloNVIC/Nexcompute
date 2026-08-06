<div align="center">

<img src="management-frontend/public/favicon.svg" alt="Nexcompute Logo" width="80">

# Nexcompute · 合算

### 把实验室散落的 Windows + GPU 主机，收敛成一个能远程调度的算力平台

**按课题组分机 · 远程管 Docker · 隔离用户存储 · 复用镜像 · 全出站穿透 NAT · TrueNAS 用户开通**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?logo=go&logoColor=white)](https://go.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/license-Internal-grey)]()

</div>

---

## 📖 这是什么

高校课题组最常见的算力窘境：一台台带 GPU 的 Windows 工作机散落在不同办公室，谁在用、跑了什么、出了问题谁负责--全靠群里喊。Nexcompute 要解决的就是**把这群机器统一成一个可调度、可审计、可按角色分配的平台**：

- 🧑‍🔬 **导师** 把某台机器分配给某个学生，学生只能动自己的容器和存储
- 🐳 **管理端** 远程拉起/重启/删除受控端上的 Docker 容器，下发 SSH 密码、端口映射、资源配额
- 📦 **镜像复用**：一个学生 commit 出的环境，授权后整个课题组可拉起，避免重复配环境
- 🔁 **全出站通信**：受控端只主动往外发心跳和 WebSocket，不开放任何入站端口--**NAT 里的机器也能被纳管**
- 🗄️ **NAS 分配**：管理员后台一键邀请门控注册 + 审批开通 TrueNAS 用户，AES-GCM 暂存密码、邮件通知、5 态审批机
- 📋 **全量审计**：任何一次非查询操作都被记录，谁在什么时候改了什么一目了然

> 名字「合算」取「合 / 算」二字--把分散的算力**合**到一起**算**。

---

## ✨ 特性亮点

| | 特性 | 说明 |
|---|---|---|
| 🔌 | **NAT 穿透式受控端** | 心跳（HTTP）+ 命令（WebSocket）双通道全出站，受控端不开任何入站端口 |
| 🐳 | **容器全生命周期** | 表单创建（SSH 密码 / CPU·内存限制 / 端口映射）、启停重启删、容器级 SSH 密码即时重置 |
| 🖥️ | **物理实例管理** | 自动注册编号、状态监控、远程重启 / 息屏 / PowerShell |
| 💾 | **存储池** | 项目级命名隔离、共享授权、跨机迁移（断点续传） |
| 📦 | **镜像管理** | 容器 commit 为 tar、归属与可见性控制、公共镜像库自动同步 |
| 🗄️ | **NAS 分配** | 邀请门控注册 + 管理员审批开通 TrueNAS 用户，AES-GCM 暂存密码、提交/激活/拒绝邮件通知、pending 过期扫描 |
| 📊 | **实时监控** | SSE 推送 + 30 日历史趋势 + 按角色查看进程列表 |
| 🎫 | **工单系统** | 五种类型、管理员回复关闭、通知提交学生 |
| 📢 | **公告与通知** | 定向 / 定时公告、未读消息收件箱、SSE 实时推送 |
| 🔒 | **全量审计** | 全局拦截器兜底 + `@Audited` 语义补充、ULID 唯一编码、不可变 |
| 📧 | **邮件通道** | SMTP 通道、品牌模板、导师邀请注册、公告已读提醒、NAS 开通通知 |
| 🔄 | **受控端 OTA** | 管理端统一下发新版本、进度实时回传、版本等待超时可调 |
| 🧰 | **环境准备** | 受控端分步引导安装 Docker Desktop、状态自检 |
| 🧑‍🏫 | **三角色权限矩阵** | 管理员 / 导师 / 学生 × 模块 × 操作（view/edit/delete）可细粒度配置 |

---

## 🏗️ 系统架构

```
                          ┌─────────────────────────────────────────────┐
                          │            管理端（单一实例）                  │
                          │   ┌───────────────┐   ┌──────────────────┐  │
                          │   │  SpringBoot   │   │  Vue3 + AntD     │  │
                          │   │   后端 API    │◄─►│  + ApexCharts    │  │
                          │   │  (Java 21)    │   │  + xterm 终端    │  │
                          │   └──┬──────┬───┬──┘   └──────────────────┘  │
                          │      │      │   │        SSE 实时推送          │
                          │   PG16  Redis7  ──► TrueNAS REST（开通用户）   │
                          │      │                                            │
                          │   ┌──┴────────────────────────────────┐     │
                          │   │  心跳 POST /api/agent/heartbeat    │     │
                          │   │  WS 命令 /api/agent/ws             │     │
                          │   │  文件传输 /api/file-transfer/*     │     │
                          │   └───────────────────────────────────┘     │
                          └──────────────────┬──────────────────────────┘
                                             │  全部为出站连接（受控端主动连出）
                          ┌──────────────────┼──────────────────┐
                          ▼                   ▼                  ▼
                   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
                   │  受控端 A    │     │  受控端 B    │     │  受控端 C    │
                   │  Go exe+GUI │     │  Go exe+GUI │     │  Go exe+GUI │
                   │  Win + GPU  │     │  Win + GPU  │     │  Win + GPU  │
                   │   Docker    │     │   Docker    │     │   Docker    │
                   └─────────────┘     └─────────────┘     └─────────────┘
                          │ NAT 后的内网机器，无需公网 IP / 端口映射即可被纳管
```

**核心数据流：**

1. 受控端启动 -> POST 心跳注册 -> 管理端分配实例编号并回传 `agentToken`
2. 受控端持 token 建立 WebSocket 长连接 -> 进入「在线，可调度」状态
3. 用户在管理端发起操作 -> 后端经 WS 派发命令 -> 受控端执行并回传结果
4. 受控端定时上报 CPU/内存/GPU/进程 -> 经 Redis 缓存热数据 + PostgreSQL 持久化历史 -> SSE 推前端
5. 管理员审批 NAS 注册 -> 后端经 TrueNAS REST API 建用户 -> 回填 id/uid -> 邮件通知（不经受控端）

---

## 📂 目录结构

```
Nexcompute/
├── management-backend/      # SpringBoot 后端（Gradle · Java 21）
│   └── src/main/
│       ├── java/com/nexcompute/management/
│       │   ├── agent/         # 受控端 WS 命令通道 / 会话注册 / OTA 进度
│       │   ├── audit/         # 审计：全局拦截器 + @Audited 注解
│       │   ├── config/        # WS / Redis / 缓存 / 异步 / 存储 / NAS 配置
│       │   ├── controller/    # 35 个 REST 控制器（含 NAS 邀请/注册/审批）
│       │   ├── domain/        # 41 个 JPA 实体（含 nas_invitation/nas_registration）
│       │   ├── filetransfer/  # 分块大文件传输
│       │   ├── security/      # JWT / 权限矩阵 / @RequirePermission
│       │   ├── service/       # 36 个业务服务（含 TrueNasClient / NasPasswordEncryptor / NasAllocationScheduler）
│       │   └── sse/           # 服务端推送
│       └── resources/db/migration/   # Flyway V1~V32 迁移脚本
├── management-frontend/     # Vue 3 + TypeScript + Vite
│   └── src/{views,api,stores,components,router,layouts,types,utils}
├── controlled-agent/        # Go 受控端（托盘 GUI + 心跳 + WS + Docker）
│   ├── cmd/nexcompute-agent/ # 入口
│   └── internal/{agent,docker,gui,heartbeat,filetransfer,autostart,power,security,storage,config}
├── openspec/               # 17 份能力规格 + 变更归档
├── docker-compose.yml       # 全栈编排（含 PG + Redis，开发/演示用）
├── docker-compose.prod.yml  # 生产部署编排（连外部 DB，不起 PG 容器）
├── DEPLOY.md                # 生产部署手册
├── build.ps1 / deploy.sh    # 一键构建部署（Win / Linux）
├── .env.example             # 环境配置模板（TrueNAS / AES / SMTP 等）
└── docs/                    # 文档
```

---

## 🧰 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| **管理端后端** | Spring Boot · JPA · Flyway · Spring Security · WebSocket · SSE · Actuator · Mail · AOP · RestClient | 3.5 |
| | Java（Gradle 构建） | 21 |
| 鉴权 | JWT（jjwt，24h）+ BCrypt + 权限矩阵拦截器 | - |
| 数据库 | PostgreSQL | 16 |
| 缓存 / 异步 | Redis · Lettuce | 7 |
| NAS 集成 | TrueNAS v2.0 REST API · AES-GCM（javax.crypto） | - |
| **管理端前端** | Vue · TypeScript · Vite | 3.5 / 5.7 / 6 |
| UI | Ant Design Vue · @ant-design/icons-vue | 4.2 |
| 图表 | ApexCharts · vue3-apexcharts | 5.1 |
| 终端 | @xterm/xterm + addon-fit | 6 |
| 状态 / 路由 | Pinia · vue-router | 2 / 4 |
| **受控端** | Go（CGO 构建） | 1.25 |
| GUI | Fyne | 2.5 |
| 托盘 | getlantern/systray | - |
| WebSocket | gorilla/websocket | 1.5 |
| Docker | docker/docker SDK | 28.5 |
| 系统指标 | gopsutil | v4 |
| **基础设施** | Docker Compose（4 服务 + 健康检查） | - |

---

## 🚀 快速开始

### 方式一：Docker Compose 一键拉起全栈（开发/演示，自带 PG + Redis）

```bash
git clone <your-repo-url> Nexcompute && cd Nexcompute

# Linux / macOS
./deploy.sh

# Windows PowerShell（同时构建受控端 exe）
.\build.ps1
# 或仅管理端：.\build.ps1 -Target Management
```

脚本会自动：本地构建后端 JAR -> 构建前后端镜像 -> `docker compose up -d` -> 健康检查。

启动后：

| 服务 | 地址 |
|---|---|
| 前端 | http://localhost |
| 后端 API | http://localhost:8080/api |
| 默认管理员 | `admin` / `admin123`（首次登录后请修改） |

### 方式二：生产部署（连外部数据库，自定义端口/地址）

镜像已打包上传至私有仓库 `10.13.66.18:5002/nexcompute/{backend,frontend}`。生产环境用 `docker-compose.prod.yml`（**不含 PG 容器，连接你的外部 PostgreSQL**），完整步骤见 [`DEPLOY.md`](./DEPLOY.md)：

```bash
# 1. 准备外部 PostgreSQL（建空库 nexcompute + 账号，Flyway 启动自动建表）
# 2. 配置 .env（TrueNAS key / AES 密钥 / SMTP）
cp .env.example .env
# 3. 编辑 docker-compose.prod.yml：填 DB 地址、JWT、CORS、前端端口
# 4. 服务器信任私有仓库（insecure-registries），见 DEPLOY.md 步骤四
# 5. 拉取启动
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

- **前端访问地址** = `http://<服务器IP>:<前端端口>`（改 `frontend.ports` 左侧）
- **后端 API**：默认走前端同源（`/api` 经 nginx 反代到后端容器），无需直连
- **数据库**：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 填外部 PG

### 方式三：开发模式（分别启动）

```bash
# 1. 基础设施
docker compose up -d postgres redis

# 2. 后端
cd management-backend
./gradlew bootRun        # 注：环境上 ./gradlew 可能挂起，建议直接用缓存的本机 gradle + JDK 21

# 3. 前端
cd ../management-frontend
npm install --legacy-peer-deps
npm run dev               # http://localhost:5173

# 4. 受控端（需 Go 1.25+ 与 CGO，Fyne 依赖）
cd ../controlled-agent
CGO_ENABLED=1 go build -o nexcompute-agent.exe ./cmd/nexcompute-agent
./nexcompute-agent.exe
```

---

## 👥 三角色与权限模型

| 角色 | 能力范围 |
|---|---|
| 🛡️ **管理员** | 全部模块全部操作：物理实例、用户、权限矩阵、PowerShell、OTA、邮件配置、审计、NAS 分配 |
| 🧑‍🏫 **导师** | 学生模块 + 课题组管理 + 把机器/资源分配给学生 |
| 🧑‍🎓 **学生** | 自己名下的物理实例状态、容器、镜像、存储池、工单、公告 |

权限不写死在代码里，而是**数据库驱动的矩阵**：角色 × 模块 × 操作（`VIEW` / `EDIT` / `DELETE`），管理员可在前端界面实时调整。后端通过声明式注解校验：

```java
@RequirePermission(module = "container", action = Action.DELETE)
public void deleteContainer(Long id) { ... }
```

`PermissionAuthorizationInterceptor` 拦截到注解后，按当前用户的角色查矩阵放行或拒绝--加新接口只要贴一行注解。

---

## 🔌 受控端通信协议

这是整个平台的「血管」。受控端**只主动往外连**，两条通道相互独立、互不阻塞：

### 通道一：心跳（HTTP）

- 受控端按固定间隔（默认 1s 本地采集、5s 上报）`POST /api/agent/heartbeat`
- 携带：CPU 占用与温度、内存（总量/已用）、**结构化 GPU**（型号、总显存、已用显存、利用率、温度）、主机全部 IP、进程列表、配置快照
- 管理端超过 `HEARTBEAT_TIMEOUT`（默认 3s）未收到 -> 标记「离线」

### 通道二：WebSocket 命令（`/api/agent/ws`）

- 受控端持 `agentToken` 主动连出，建立长连接
- 管理端经此通道派发命令，受控端同连接回传结果（按 `commandId` 关联）
- 断连后**心跳照发**，并按指数退避（`2000ms -> 60000ms`）重连，重连后管理端同步状态
- WS 消息缓冲区放大到 2MB（进程列表等大回包）

**双向互信鉴权**：管理端用 JWT 校验用户；受控端用 `agentToken` 校验命令来源（防伪管理端派发恶意命令），管理端用 token 校验受控端身份（防流氓受控端接入）。

### 命令清单（29 类）

| 域 | 命令 |
|---|---|
| **容器** | `container.create` `container.start` `container.stop` `container.restart` `container.rm` `container.logs` `container.reset_ssh` |
| **系统** | `system.restart` `system.screen_off` `system.powershell` |
| **镜像** | `image.commit` `image.load` `image.sync_public` |
| **存储** | `storage.create_dir` `storage.list_files` `storage.delete_dir` `storage.archive` `storage.upload_file` `storage.migration_download` `storage.migration_upload` `storage.migration_cleanup` |
| **终端** | `terminal.open` `terminal.read` `terminal.write` `terminal.close` |
| **其他** | `env.sync` `port.query_used` `process.list` |

---

## 🧩 核心功能详解

### 🐳 容器生命周期
表单化创建--填好镜像、SSH 密码、CPU/内存上限、端口映射，管理端先下发 `image.load`（受控端检查本地是否已持有该镜像，已持有则跳过；否则经文件通道下载 tar 并 `docker load`），成功后再创建容器。运行中可启停重启删、即时重置容器级 SSH 密码、查看日志、打开 xterm 终端。

### 💾 存储池
按「项目」命名隔离，避免不同课题组互踩。支持共享授权、归档；**跨机迁移走断点续传**：分块上传/下载 + 中转目录 + 迁移完成清理（`storage.migration_*`）。存储根目录设定后锁定，改需本地管理员密码。

### 📦 镜像管理（tar 方案，无 registry pull）
镜像以 tar 文件形式在管理端存储与分发：
- **私有镜像**：`{STORAGE_ROOT}/images/{userId}/{name}-{tag}.tar`
- **公共镜像**：`{STORAGE_ROOT}/public-images/{name}-{tag}.tar`
- 受控端接收的 tar 落在 `os.TempDir()`，`docker load` 后即删，不持久化

学生把容器 `image.commit` 成镜像后，可控制可见性（私有 / 授权给特定课题组 / 捐入公共库）。公共镜像库由受控端 `image.sync_public` 定期同步（默认 1h）。

### 🗄️ NAS 分配（TrueNAS 用户开通）
管理员后台「NAS分配」模块：邀请门控的 TrueNAS 用户注册 + 管理员审批开通全流程，管理端**直接调 TrueNAS REST API**（不经受控端）：
- **邀请管理**：管理员创建带标签/名额/有效期的邀请链接，返回完整注册链接（按当前管理端访问地址动态生成）
- **公开注册**：用户凭邀请令牌提交申请（username POSIX 校验 + 姓名/邮箱/手机/密码），AES-GCM 加密密码暂存（密钥 `PASSWORD_ENC_KEY`），**不调 TrueNAS**；输入用户名后 onBlur 实时检查 TrueNAS + 本地占用
- **名额原子消耗**：单条条件 UPDATE（`used_count < max_uses AND revoked_at IS NULL AND expires_at > now`），并发不超发
- **审批开通**：管理员批准 -> 解密密码 -> TrueNAS 幂等查重 -> 建/回填用户 -> 擦除密码 -> `APPROVED`；失败 `FAILED` 保留密码可重试；支持改角色（保留 builtin_users）/重申上游（NOT_FOUND 重建）/批量刷新状态
- **5 态机**：`PENDING` / `APPROVED` / `REJECTED` / `FAILED` / `NOT_FOUND`
- **邮件通知**：提交注册 / 激活 / 拒绝 均发邮件通知申请者邮箱
- **pending 过期扫描**：`@Scheduled` 每 6h 扫描超 `pendingExpireDays`（默认 7 天）的 PENDING，擦密码置 `REJECTED`
- **自签 TLS**：`verifyTls=false` 时信任全部 SSLContext；启动 ping TrueNAS 仅 WARN 不阻断

### 📊 监控
受控端采集 -> Redis 缓存热数据（5s 粒度）-> PostgreSQL 持久化 30 日历史 -> 前端经 **SSE** 实时刷新图表。支持按角色查看进程列表。历史数据有定时清理任务避免膨胀。

### 🎫 工单系统
学生提交五类工单（容器异常 / 存储问题 / 权限申请 / 资源扩容 / 其他），管理员回复后可关闭，关闭时通知提交学生。

### 📢 公告与通知
管理员发定向（按角色/课题组）或定时公告；学生侧有未读消息收件箱，永久保留；公告已读状态实时回传。前端 `RealtimeToast` + `AnnouncementLoginModal` 经 SSE 即时弹出。

### 🔒 审计日志（不可绕过）
- **全局拦截器兜底**：任何非 GET 写操作都被记录，**不依赖人工注解**，覆盖率 100%
- **`@Audited` 补充语义**：service 层注解补充操作类型 / 目标类型 / 目标 ID；已被注解记录的请求，拦截器跳过避免重复
- 每条记录含：操作时间、用户（ID/姓名/角色）、action、参数快照、目标、结果（SUCCESS/FAILURE）+ 失败信息、客户端（UA + IP，受控端操作含实例编号）、**ULID 唯一编码 `operationNo`**、操作时导师归属快照 `mentorIdAtOp`
- 审计表不可变（`audit_immutable`），审计查询接口本身不产生审计记录

### 📧 邮件通道
SMTP（默认 smtps/465）+ 品牌模板（Logo / 落款 / 品牌名可配）。触发时机包括：导师邀请学生注册、公告已读提醒、工单状态变更、**NAS 注册提交/激活/拒绝通知**。每个用户可开关自己的邮件偏好（`UserEmailPref`）。首启经 Flyway 种子入 `system_config`，运行时读 DB。

### 🔄 受控端 OTA
管理端统一构建新版本 exe -> 下发升级命令 -> 受控端经文件通道下载 -> 替换并重启 -> 回传新版本号。进度经 `OtaProgressTracker` 实时回传前端。版本等待超时可调（默认 180s，避免大体积 exe 升级误判失败）。

### 🧰 受控端环境准备
受控端 GUI 分步引导：检测 Docker Desktop 是否安装 -> 引导下载安装器（`env.sync` 同步状态）-> 状态自检 -> 进入正常工作。`EnvFileController` 支持上传 ~600MB 的安装器文件（multipart 上限调到 2GB）。

---

## ⚙️ 关键配置项

配置经根目录 `.env` 注入（Spring `spring.config.import` + docker-compose `env_file`，**值不要加引号**），均可覆盖 `application.yml` 默认值：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 本地 pg | PostgreSQL 连接 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost:6379 | Redis 连接 |
| `JWT_SECRET` | 内置占位 | **生产务必替换** |
| `STORAGE_ROOT` | `./data/storage` | 镜像 / 迁移文件根目录 |
| `MONITORING_INTERVAL` / `MONITORING_RETENTION_DAYS` | 5 / 30 | 监控采集粒度（秒）/ 历史保留天数 |
| `HEARTBEAT_TIMEOUT` / `HEARTBEAT_INTERVAL` | 3 / 5 | 心跳超时判离线 / 受控端心跳间隔（秒） |
| `FILE_CHUNK_SIZE` | 4194304 | 文件分块大小（4MB） |
| `UPGRADE_VERSION_WAIT_TIMEOUT_MS` | 180000 | OTA 等待新版本回传超时 |
| `CORS_ORIGINS` | localhost:5173,3000 | 允许的前端来源 |
| `EMAIL_HOST` / `EMAIL_PORT` / `EMAIL_USER` / `EMAIL_PASSWD` | - | SMTP 配置 |
| `TRUENAS_BASE_URL` / `TRUENAS_API_KEY` | - | TrueNAS REST 连接（API key 须 ACCOUNT_WRITE 角色） |
| `TRUENAS_VERIFY_TLS` / `TRUENAS_TIMEOUT_SECONDS` / `TRUENAS_RETRIES` | false / 30 / 2 | 自签 TLS 关校验 / 超时 / 重试 |
| `TRUENAS_USER_HOME_PARENT` | 空 | 新用户 home 父目录（设置则开 SSH，否则仅 SMB） |
| `PASSWORD_ENC_KEY` | - | AES-GCM 密钥（base64-urlsafe，16/24/32 字节） |
| `NAS_PORTAL_BASE_URL` | localhost:5173 | 注册页基址（回退用；优先取当前请求地址） |
| `NAS_PENDING_EXPIRE_DAYS` / `NAS_EXPIRY_SCAN_INTERVAL_HOURS` | 7 / 6 | pending 过期天数 / 扫描间隔 |

完整模板见 [`.env.example`](./.env.example)。

---

## 🗄️ 数据库与迁移

- DDL 由 **Flyway** 管理，`hibernate.ddl-auto=validate`（只校验不自动改表）
- **32 个迁移脚本 `V1`~`V32`**，覆盖：基础 schema、访问控制、物理实例、资源分配、存储池、容器、镜像、端口分配、监控、工单、通知、资源配额、容器共享/备注、工单编号、系统信息、实例指纹、环境/OTA/实时、审计不可变、邮件通知、邮件触发、导师邀请注册、管理员邀请注册、容器内挂载点、**NAS 分配（nas_invitation / nas_registration）** 等
- `baseline-on-migrate=true`，已有库可平滑接入

---

## 🧪 测试

```bash
# 后端单元测试（含 Testcontainers · PostgreSQL）
cd management-backend && ./gradlew test

# 受控端 Go 测试
cd controlled-agent && go test ./...
```

端到端联调用例见 `openspec/changes/archive/`。

---

## 🗂️ 规格与变更记录

项目采用 [OpenSpec](https://github.com/Fission-AI/OpenSpec) 工作流，能力以规格形式固化在 `openspec/specs/`，共 **17 份**：

```
access-control      agent-communication   agent-env-prep   agent-ota
audit               container-lifecycle    controlled-agent email-notification
file-transfer       image-management       monitoring        nas-allocation
notifications       physical-instance      resource-allocation
storage-pool        ticket-system
```

每次迭代落一份变更记录到 `openspec/changes/archive/`，可追溯每个特性从设计到落地的全过程。

---

## 🛣️ 路线图

- [x] 三角色 + 权限矩阵
- [x] 受控端全出站通信（心跳 + WS）
- [x] 容器 / 存储 / 镜像全生命周期
- [x] 监控 + 审计 + 工单 + 公告
- [x] 邮件通道 + 导师/管理员邀请注册
- [x] 受控端 OTA + 环境准备
- [x] NAS 分配（TrueNAS 用户邀请门控注册 + 审批开通 + 邮件通知）
- [ ] 穿透连接模式（`tunnel`，当前标记「敬请期待」）
- [ ] 资源配额的更细粒度自动限制
- [ ] 多管理端实例水平扩展

---

## 📄 License

本项目当前为内部使用，暂未开源。如需引用或二次开发，请联系维护者。

---

<div align="center">

<sub>构建于 JDK 21 · Go 1.25 · Node 22 · PostgreSQL 16 · Redis 7</sub>

</div>
