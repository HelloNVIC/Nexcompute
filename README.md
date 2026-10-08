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
- 📦 **镜像复用**：环境经内网私有仓库「一次推送、全网可用」，学生 commit 出的环境授权后整个课题组可拉起，避免重复配环境
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
| 📦 | **镜像管理** | 私有仓库登记 + 推送命令引导、有效性检查、无标记镜像补录、容器 commit 直推仓库、创建容器实时拉取进度、存量 tar 兼容 |
| 🗄️ | **NAS 分配** | 邀请门控注册 + 管理员审批开通 TrueNAS 用户，AES-GCM 暂存密码、提交/激活/拒绝邮件通知、pending 过期扫描 |
| 🤖 | **NewAPI 分配** | 邀请门控注册 + 审批开通 LLM 网关用户/Token，选分组开通、上游复核、失败重试、邮件通知 |
| 📊 | **实时监控** | SSE 推送 + 30 日历史趋势 + 按角色查看进程列表 |
| 🎫 | **工单系统** | 五种类型、管理员回复关闭、通知提交学生 |
| 📢 | **公告与通知** | 定向 / 定时公告、未读消息收件箱、SSE 实时推送 |
| 🔒 | **全量审计** | 全局拦截器兜底 + `@Audited` 语义补充、ULID 唯一编码、不可变 |
| 📧 | **邮件通道** | SMTP 通道、品牌模板、导师邀请注册、公告已读提醒、NAS 开通通知 |
| 🔄 | **受控端 OTA** | 管理端统一下发新版本、进度实时回传（进度通道同时承载镜像拉取）、版本等待超时可调 |
| 🧰 | **环境准备** | 受控端分步引导安装 Docker Desktop、私有仓库 daemon 配置一键复制、单实例运行、状态自检 |
| 🧑‍🏫 | **三角色权限矩阵** | 管理员 / 导师 / 学生 × 模块 × 操作（view/edit/delete）可细粒度配置 |
| 🔑 | **账号自助** | 用户信息自助改密、忘记密码邮箱验证码重置（防枚举/限频/尝试上限）、注册页工号即时校验 |

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
                          │   PG16  Redis7  ──► TrueNAS/NewAPI REST · Registry v2   │
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
5. 管理员审批 NAS/NewAPI 注册 -> 后端直调 TrueNAS / NewAPI REST API 开通账号 -> 邮件通知（不经受控端）
6. 镜像走内网私有仓库：管理端登记并校验有效性，创建容器时受控端 `docker pull` 直连仓库并回传拉取进度

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
│       │   ├── controller/    # 38 个 REST 控制器（含 NAS/NewAPI 邀请/注册/审批）
│       │   ├── domain/        # 45 个 JPA 实体（含 nas/newapi 邀请与注册、password_reset_otp）
│       │   ├── filetransfer/  # 分块大文件传输
│       │   ├── registry/      # 私有仓库 Registry v2 API 客户端（有效性检查/枚举）
│       │   ├── security/      # JWT / 权限矩阵 / @RequirePermission
│       │   ├── service/       # 47 个业务服务（含 TrueNasClient / NewApiClient / NasPasswordEncryptor）
│       │   └── sse/           # 服务端推送
│       └── resources/db/migration/   # Flyway V1~V36 迁移脚本
├── management-frontend/     # Vue 3 + TypeScript + Vite
│   └── src/{views,api,stores,components,router,layouts,types,utils}
├── controlled-agent/        # Go 受控端（托盘 GUI + 心跳 + WS + Docker）
│   ├── cmd/nexcompute-agent/ # 入口
│   └── internal/{agent,docker,gui,heartbeat,filetransfer,autostart,power,security,storage,config}
├── openspec/               # 19 份能力规格 + 变更归档
├── docker-compose.yml           # 全栈编排（DEV，含真实密钥 -> gitignore；由 .example 复制填值）
├── docker-compose.example.yml   # 全栈编排模板（占位密钥，入库）
├── docker-compose.prod.yml      # 生产编排（真实密钥 -> gitignore；连外部 DB，不起 PG 容器）
├── docker-compose.prod.example.yml  # 生产编排模板（占位密钥，入库）
├── DEPLOY.md                    # 生产部署手册
├── build.ps1 / deploy.sh        # 一键构建部署（Win / Linux）
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
| LLM 网关 / 镜像仓库 | NewAPI REST（Token 分配）· 内网私有 Registry v2 API（镜像有效性/枚举） | - |
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
# 2. 从模板复制并填值：cp docker-compose.prod.example.yml docker-compose.prod.yml
#    vi docker-compose.prod.yml   # 填 DB / JWT / CORS / TrueNAS / AES / SMTP（真实文件 gitignore 不入库）
# 3. 服务器信任私有仓库（insecure-registries），见 DEPLOY.md 步骤四
# 4. 拉取启动
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
./gradlew bootRun        # 注：./gradlew 可能挂起，建议用缓存的本机 gradle + JDK 21；bootRun 用 application.yml 默认值（不再读根 .env），NAS/邮件密钥可在 IDE 运行配置或 shell export 注入

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

### 🔑 账号自助管理

- **自助改密**：「用户信息」页校验旧密码后自助修改（≥6 位含字母与数字、新旧不同，恒记审计）
- **忘记密码**：登录页凭工号/学号向绑定邮箱发 6 位数字验证码重置；验证码 BCrypt 哈希存储、默认 10 分钟有效、60s 限频、每码 5 次尝试上限；统一响应防账号枚举
- **注册即时校验**：三套邀请注册页输入工号/学号 onBlur 即时查重（先验邀请链接有效，不泄露占用状态）

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

### 命令清单（34 类）

| 域 | 命令 |
|---|---|
| **容器** | `container.create` `container.start` `container.stop` `container.restart` `container.rm` `container.logs` `container.reset_ssh` |
| **系统** | `system.restart` `system.screen_off` `system.powershell` |
| **镜像** | `image.commit` `image.pull` `image.load` `image.sync_public` |
| **存储** | `storage.create_dir` `storage.list_files` `storage.delete_dir` `storage.archive` `storage.upload_file` `storage.migration_download` `storage.migration_upload` `storage.migration_cleanup` |
| **终端** | `terminal.open` `terminal.read` `terminal.write` `terminal.close` |
| **受控端** | `agent.upgrade` `agent.log` `config.set_admin_password` |
| **其他** | `env.sync` `port.query_used` `process.list` `file.upload` `file.download` |

---

## 🧩 核心功能详解

### 🐳 容器生命周期
表单化创建--填好镜像、SSH 密码、CPU/内存上限、端口映射。镜像分发按类型走：**仓库类镜像**下发 `image.pull`（受控端本地已持有则跳过，否则从私有仓库 `docker pull`），拉取进度经 SSE 实时回传前端；**存量 tar 镜像**仍下发 `image.load`（经文件通道下载 tar 并 `docker load`），行为不变。镜像就绪后再创建并启动容器。运行中可启停重启删、即时重置容器级 SSH 密码、查看日志、打开 xterm 终端。

### 💾 存储池
按「项目」命名隔离，避免不同课题组互踩。支持共享授权、归档；**跨机迁移走断点续传**：分块上传/下载 + 中转目录 + 迁移完成清理（`storage.migration_*`）。存储根目录设定后锁定，改需本地管理员密码。

### 📦 镜像管理（私有仓库分发 + 存量 tar 兼容）
镜像分发以**内网私有仓库**（`REGISTRY_URL`，默认 `10.13.66.25:5000`）为主通道，实现「一次推送、全网可用」：

- **登记 + 自行推送**：用户登记镜像元数据（原始镜像名/标签/应用端口/容器内挂载/使用说明），系统展示 `docker tag` + `docker push` 命令供本机执行，可一键复制
- **有效性检查**：管理端经 Registry v2 API 检查镜像是否确已推送，仅有效镜像可用于创建容器；「刷新状态」即时重查，仓库不可达时不误标为无效
- **容器 commit 直推仓库**：受控端 `docker commit` 后直接 `tag` + `push` 到仓库（命名沿用 `工号-项目-镜像名-标签-备注-随机串` 规则派生），不再导出 tar 回传
- **创建容器实时拉取**：管理端下发 `image.pull`，受控端 `docker pull` 并逐层回传拉取进度（SSE 推前端）
- **同步到所有机器**（仅管理员）：镜像列表一键向全部**在线**实例下发 `image.pull`（离线实例标记跳过），批次与每实例任务落库（`image_sync_batch` / `image_sync_task`，每镜像同时仅一个进行中批次），抽屉实时展示各机器进度（SSE `imageSyncProgress`），终态含 拉取中/已存在/完成/失败/超时/离线跳过；刷新页面可恢复进行中批次视图
- **无标记镜像补录**：管理员可枚举仓库中存在但系统未登记的镜像，补录元数据转为可用
- **存量 tar 兼容**：已有 tarPath 记录仍走 file-transfer + `docker load`；公共镜像库定期同步（`image.sync_public`，默认 1h）保留

受控端侧需在 Docker daemon 配置 `insecure-registries` 指向该仓库（环境准备栏目一键复制配置片段）；受控端单实例运行，重复启动自动退出。

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

### 🤖 NewAPI 分配（LLM 网关 Token 开通）
与 NAS 分配同构的「Token分配」模块，上游换为 NewAPI（LLM 网关）REST API：

- **邀请门控注册**：名额原子消耗（条件 UPDATE），表单含用户名/姓名/邮箱/手机号/密码，提交不调 NewAPI
- **AES-GCM 密码暂存**：复用 `PASSWORD_ENC_KEY`，审批通过后解密开通并擦除；用户名本地占用 + NewAPI `search` 双重查重
- **审批开通**：管理员选 NewAPI `group` 分组（决定可访问渠道/模型）-> 建用户（响应无 id，经 `search` 回查回填）-> `APPROVED`；失败 `FAILED` 保留密码可重试
- **上游复核**：详情/刷新状态批量复核已开通用户（上游被删翻 `NOT_FOUND`，可用新密码重申重建）；支持改分组（不动 quota）
- **pending 过期扫描**：`@Scheduled` 扫描超期 PENDING，擦密码置 `REJECTED`
- **集成**：`NewApiClient` 基于 Spring `RestClient` 同步调用（http，无需 TLS 特判），Bearer 令牌 + `New-Api-User` 头

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
SMTP（默认 smtps/465）+ 品牌模板（Logo / 落款 / 品牌名可配）。触发时机包括：导师邀请学生注册、忘记密码验证码、公告已读提醒、工单状态变更、**NAS 注册提交/激活/拒绝通知**。每个用户可开关自己的邮件偏好（`UserEmailPref`）。首启经 Flyway 种子入 `system_config`，运行时读 DB。

### 🔄 受控端 OTA
管理端统一构建新版本 exe -> 下发升级命令 -> 受控端经文件通道下载 -> 替换并重启 -> 回传新版本号。进度经通用进度路由（`ProgressRouter`，同样承载镜像拉取等长任务进度）实时回传前端。版本等待超时可调（默认 180s，避免大体积 exe 升级误判失败）。

### 🧰 受控端环境准备
受控端 GUI 分步引导：检测 Docker Desktop 是否安装 -> 引导下载安装器（`env.sync` 同步状态）-> 状态自检 -> 进入正常工作。另提供「配置私有镜像仓库」一键复制 `insecure-registries` 配置片段；受控端单实例运行，重复启动自动退出。`EnvFileController` 支持上传 ~600MB 的安装器文件（multipart 上限调到 2GB）。

受控端本地行为要点：**Docker Desktop 看门狗**每 30 秒检查 daemon 可达性，不可达自动拉起（拉起后 60 秒冷却防启动期进程风暴，失败日志限流）；**存储池根目录默认 `D:\lab404`**（新装与存量空值自动补填，不锁定、仍可经管理员密码修改）；**配置文件仅内容变化才写盘**（心跳回包密码未变化不触发重写，手动编辑不再被覆盖，原子写防损坏）；登录失效（token 过期）时管理端统一返回 **HTTP 401 + 业务码 1003**，前端提示「登录已失效」并登出跳转（并发仅提示一次），与权限拒绝（403，提示真实原因不登出）明确区分。

---

## ⚙️ 关键配置项

配置经 docker-compose 的 `environment:` 注入（DEV 用 `docker-compose.yml`，生产用 `docker-compose.prod.yml`，模板见 `docker-compose.example.yml`），均可覆盖 `application.yml` 默认值；本地 bootRun 则用 `application.yml` 默认值或 shell 环境变量：

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
| `REGISTRY_URL` | 10.13.66.25:5000 | 内网私有镜像仓库（用户镜像 push/pull 与 /v2 有效性检查同端口；受控端 daemon 需配 insecure-registries，见 DEPLOY.md 步骤四） |
| `NEWAPI_BASE_URL` / `NEWAPI_ACCESS_TOKEN` / `NEWAPI_API_USER` | - | NewAPI REST 连接（基址 + 系统访问令牌 + 调用方用户 id） |
| `NEWAPI_PENDING_EXPIRE_DAYS` / `NEWAPI_EXPIRY_SCAN_INTERVAL_HOURS` | 7 / 6 | NewAPI pending 过期天数 / 扫描间隔 |

完整模板见 [`docker-compose.example.yml`](./docker-compose.example.yml)。

---

## 🗄️ 数据库与迁移

- DDL 由 **Flyway** 管理，`hibernate.ddl-auto=validate`（只校验不自动改表）
- **36 个迁移脚本 `V1`~`V36`**，覆盖：基础 schema、访问控制、物理实例、资源分配、存储池、容器、镜像、端口分配、监控、工单、通知、资源配额、容器共享/备注、工单编号、系统信息、实例指纹、环境/OTA/实时、审计不可变、邮件通知、邮件触发、导师邀请注册、管理员邀请注册、容器内挂载点、**NAS 分配（nas_invitation / nas_registration）**、NewAPI 分配、忘记密码验证码、**私有仓库镜像分发（image_metadata 增 distribution/registry_valid/registry_checked_at）**、**镜像同步批次（image_sync_batch / image_sync_task）** 等
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

项目采用 [OpenSpec](https://github.com/Fission-AI/OpenSpec) 工作流，能力以规格形式固化在 `openspec/specs/`，共 **19 份**：

```
access-control          agent-communication     agent-env-prep       agent-ota
audit                   container-lifecycle     controlled-agent     email-notification
file-transfer           image-management        monitoring           nas-allocation
newapi-user-allocation  notifications           physical-instance    registry-image-management
resource-allocation     storage-pool            ticket-system
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
- [x] NewAPI Token 分配（LLM 网关用户/令牌开通）
- [x] 私有仓库镜像分发（登记/推送引导/有效性检查/无标记补录 + commit 直推 + 拉取进度）
- [x] 账号自助（自助改密 / 忘记密码邮箱验证码 / 注册工号即时校验）
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
