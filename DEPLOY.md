# Nexcompute 管理端 部署手册

本文档指导你将管理端部署到服务器：**使用私有仓库镜像**、**连接外部 PostgreSQL（不起 PG 容器）**、**自定义前后端地址与端口**。

---

## 1. 部署架构

```
用户浏览器
   │  http://<服务器IP>:<前端端口>
   ▼
┌─────────────────────────────────────────────┐
│  服务器（Docker）                            │
│                                             │
│  ┌──────────────┐   /api/   ┌────────────┐  │
│  │ frontend     │ ────────▶ │ backend    │  │
│  │ nginx:80     │  反代      │ SpringBoot │  │
│  │ (静态+反代)  │            │ :8080      │  │
│  └──────────────┘            └─────┬──────┘  │
│         ▲                          │         │
│         │                          ▼         │
│    宿主机端口                外部 PostgreSQL  │
│   <前端端口>:80              <PG_HOST>:5432   │
│                              外部 Redis(可选)│
└─────────────────────────────────────────────┘
```

- **前端**：nginx 静态服务 + 把 `/api/*` 反向代理到后端容器。前端访问后端走**同源**（`http://<前端地址>/api/...`），无需配置跨域。
- **后端**：SpringBoot，容器内固定 `:8080`，端口经 compose 映射。连接外部 PostgreSQL 与 Redis。
- **数据库**：连接你已有的 PostgreSQL（**不起 PG 容器**）。后端启动时 Flyway 自动建表/迁移。

镜像均来自私有仓库 `10.13.66.18:5002/nexcompute/backend`、`…/frontend`（见 `build.ps1`）。

---

## 2. 前置条件

1. **服务器**：Linux（推荐）或 Windows + Docker Desktop，已安装：
   - `Docker 24+`
   - `docker compose` 插件（v2）
2. **私有仓库可达**：服务器能访问 `10.13.66.18:5002`（HTTP 私有仓库，需配 insecure-registries，见步骤 4）。
3. **外部 PostgreSQL**：已有 PG 实例（`<PG_HOST>:<PG_PORT>`），并能创建库与账号。
4. **外部 Redis（可选）**：若有现成 Redis 可用；没有也没关系，部署文件里自带一个 Redis 容器。

---

## 3. 步骤一：准备数据库

在已有 PostgreSQL 上为管理端创建空库与账号（后端启动时 Flyway 自动建表）：

```sql
-- 用超级用户连接 PG，执行：
CREATE DATABASE nexcompute ENCODING 'UTF8';
CREATE USER nexcompute WITH PASSWORD '你的强密码';
GRANT ALL PRIVILEGES ON DATABASE nexcompute TO nexcompute;

-- 连到 nexcompute 库，授予建表权限（Flyway 迁移需要）：
\c nexcompute
GRANT ALL ON SCHEMA public TO nexcompute;
ALTER DATABASE nexcompute OWNER TO nexcompute;  -- 可选：让 nexcompute 成为库 owner
```

记下：`PG_HOST`、`PG_PORT`（默认 5432）、`PG_USER`（如 nexcompute）、`PG_PASSWORD`。

> 数据库无需手动建表。后端首次启动会经 Flyway 执行 V1～V32 迁移，自动建立全部表结构。

---

## 4. 步骤二：编辑 docker-compose.prod.yml 填入配置

仓库入库的是模板 `docker-compose.prod.example.yml`；真实 `docker-compose.prod.yml`（含真实密钥）已 gitignore 不入库。部署时从模板复制一份再填值：

```bash
cp docker-compose.prod.example.yml docker-compose.prod.yml   # 真实文件 gitignore，不入库
vi docker-compose.prod.yml      # 全文搜 `<` 定位占位并填真实值
```

全部环境配置已内联在 `backend.environment:`（**不再使用 `.env`**）。需替换的占位：

| 占位 | 说明 | 示例 |
|---|---|---|
| `<PG_HOST>` / `<PG_PORT>` | 外部 PostgreSQL 主机 / 端口 | `10.13.66.18` / `5432` |
| `<PG_USER>` / `<PG_PASSWORD>` | PG 账号 / 密码 | `nexcompute` / 强密码 |
| `<change-to-strong-random-secret>` | JWT 签名密钥（>=64 字符） | `openssl rand -base64 48` 生成 |
| `<truenas-host>` | TrueNAS REST 地址 | `10.13.66.23` |
| `<truenas-api-key>` | TrueNAS API key（须 ACCOUNT_WRITE 角色） | `2-xxxx...` |
| `<base64-urlsafe-32byte-key>` | AES-GCM 密码加密密钥 | 见下方生成命令 |
| `<smtp-from-email>` / `<smtp-host>` / `<smtp-user>` / `<smtp-auth-code>` | SMTP 发件 / 主机 / 账号 / 授权码 | `cufel@cufe.edu.cn` / `smtp.exmail.qq.com` / ... |
| `<服务器IP>` | 部署服务器对外 IP（CORS / NAS 注册页基址） | `192.168.1.50` |

**AES 密钥生成**（base64-urlsafe 32 字节）：

```bash
python -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())"
```

> Redis：默认用模板自带的 redis 容器（`REDIS_HOST: redis`，无密码）；若连外部 Redis，删掉 `redis` 服务，把 `REDIS_HOST` 改为外部地址并填 `REDIS_PASSWORD`。
> 前端端口默认 `80:80`（访问 `http://<服务器IP>`）；改端口改 `frontend.ports` 左侧。
> YAML 中已带引号的值保持引号；新增值若含 `:`、空格、`#` 等特殊字符需加引号。
> 若需新用户开 SSH 登录，取消 `# TRUENAS_USER_HOME_PARENT: "/mnt/tank/home"` 注释并填 home 父目录（空 = 仅 SMB）。

---

## 5. 步骤三：端口与地址 / Redis

> 配置与密钥填法见上一步（步骤二）；本步只讲端口、访问地址与 Redis。

### 端口与地址怎么改

- **前端访问地址** = `http://<服务器IP>`（默认 `frontend.ports: 80:80`，改端口改左侧）。
- **后端 API**：默认经前端 nginx(80) 反代 `/api`（同源 `http://<服务器IP>/api/...`）；同时默认暴露 `backend.ports: 8080:8080` 供**受控端直连**（ServerURL `http://<服务器IP>:8080`）与直连 API 调试。不需要直连可注释掉该端口（受控端则改连 `http://<服务器IP>/api/agent/*`）。
- **`CORS_ORIGINS`**：填用户浏览器访问前端的地址（同源时也填，保险）。多个逗号分隔，如 `http://192.168.1.50,http://192.168.1.50:8080`。
- **改后端容器端口**（一般不用）：后端容器内固定 `8080`，nginx 反代目标也是 `backend:8080`，无需改动。

### Redis

- 用自带 Redis 容器（默认）：保持 `redis` 服务 + `backend.REDIS_HOST: redis`。
- 用外部 Redis：删掉 `redis` 服务，把 `backend.REDIS_HOST` 改成外部 Redis 地址，`REDIS_PORT` 对应。

---

## 6. 步骤四：配置 Docker 信任私有仓库

私有仓库 `10.13.66.18:5002`（后端/前端镜像分发）与 `10.13.66.25:5000`（用户镜像分发，registry-image-distribution）均为 HTTP，需让 Docker 信任它们（insecure-registries）。

**Linux 服务器**：编辑 `/etc/docker/daemon.json`（没有则新建）：

```json
{
  "insecure-registries": ["10.13.66.18:5002", "10.13.66.25:5000"]
}
```

重启 Docker：

```bash
sudo systemctl restart docker
```

验证可拉取：

```bash
docker pull 10.13.66.18:5002/nexcompute/frontend:latest
# 看到 Status: Downloaded / Image is up to date 即成功
```

**Windows + Docker Desktop**：Settings → Docker Engine → 在 JSON 里加 `"insecure-registries": ["10.13.66.18:5002", "10.13.66.25:5000"]` → Apply & Restart。

> **受控端主机同样需要**配置 `10.13.66.25:5000` 为 insecure-registry（用户镜像经该仓库 push/pull 分发）。受控端"环境准备"栏目提供"9. 配置私有镜像仓库"引导按钮（弹窗展示追加片段并支持一键复制），详见 `docs/受控端环境准备.md`。

---

## 7. 步骤五：拉取镜像并启动

在部署目录（含 `docker-compose.prod.yml`）执行：

```bash
# 拉取最新镜像
docker compose -f docker-compose.prod.yml pull

# 后台启动
docker compose -f docker-compose.prod.yml up -d
```

查看启动状态：

```bash
docker compose -f docker-compose.prod.yml ps
docker logs -f nexcompute-backend    # 看后端日志，等待 "Started ManagementApplication"
```

首次启动约 30～90 秒（Flyway 迁移 + Spring Boot 初始化）。

---

## 8. 步骤六：验证

1. **前端**：浏览器打开 `http://<服务器IP>:<前端端口>`，应见登录页。
2. **登录**：默认管理员 `admin` / `admin123`（**上线后务必改密码**）。
3. **后端健康**：`curl http://<服务器IP>:<前端端口>/api/ping` 应返回 `{"code":0,...}`。
4. **NAS 模块**：登录后进「NAS分配」，创建邀请 → 复制注册链接（应为你访问的 `<服务器IP>:<前端端口>/nas-register?token=...`）→ 打开注册页提交申请 → 审批。
5. **TrueNAS 连通**：后端日志应见 `[TrueNAS] 启动 ping 成功: http://<TRUENAS_BASE_URL>`。

---

## 9. 常见问题

### Q1：后端启动失败 `password authentication failed for user nexcompute`
`docker-compose.prod.yml` 里 `backend.DB_PASSWORD` 与外部 PostgreSQL 实际密码不一致，或值首尾被引号 / 空格污染。YAML 会剥离单层引号（`"x"` -> `x`），但多层引号（如 `'\"x\"'`）会把引号当值。保持 `DB_PASSWORD: 你的密码` 即可，密码含 `@`/`=` 无需加引号。

### Q2：前端能打开但登录提示"网络异常" / `/api` 404
- 确认 `nexcompute-backend` 容器已 `Started`：`docker logs nexcompute-backend`。
- 确认前端与后端在**同一 compose 网络**（`docker-compose.prod.yml` 里两者都在 `nexcompute` 网络）。
- nginx 反代目标是 `http://backend:8080`（service 名 `backend`），勿改 service 名。

### Q3：CORS 报错 / 跨域
`backend.environment.CORS_ORIGINS` 必须包含用户实际访问前端的地址（协议+IP+端口）。如访问 `http://192.168.1.50:8080`，则 `CORS_ORIGINS=http://192.168.1.50:8080`。改后 `docker compose -f docker-compose.prod.yml up -d backend` 重启后端。

### Q4：TrueNAS ping 失败 / `user.create` 401
- `TRUENAS_BASE_URL` 必须后端容器**能访问**到的地址（若 TrueNAS 在内网，确保服务器与 TrueNAS 网络通）。
- `TRUENAS_API_KEY` 须属 ACCOUNT_WRITE 角色（否则 `user.create` 在审批时被拒）。启动 ping 失败**不阻断**启动，仅 WARN，审批时才暴露。

### Q5：后端端口 / 受控端直连
`docker-compose.prod.yml` 默认暴露 `backend.ports: 8080:8080`，后端 API 直连 `http://<服务器IP>:8080/api`，受控端 ServerURL 用 `http://<服务器IP>:8080`。改端口改左侧（如 `- "9000:8080"` -> `http://<IP>:9000`）；若仅用前端 nginx(80) 反代 /api（受控端改连 `http://<服务器IP>/api/agent/*`），可注释掉该端口。

### Q6：前后端分机部署（前端 A 机、后端 B 机）
前端 nginx 把 `/api` 反代到 `backend:8080`（同 compose service 名）。**分机时**需改 `management-frontend/nginx.conf` 的 `proxy_pass http://backend:8080` 为后端实际地址并**重建前端镜像**。**推荐同机部署**（用一个 compose 文件起 frontend+backend）避免改 nginx。

### Q7：如何升级镜像
```bash
docker compose -f docker-compose.prod.yml pull      # 拉最新
docker compose -f docker-compose.prod.yml up -d     # 滚动重启
```
指定版本：`VERSION=v1.2.0 docker compose -f docker-compose.prod.yml up -d`（镜像由 `build.ps1` 推送时打的 tag）。

### Q8：如何 HTTPS
在前端容器前再放一层反向代理（如宿主机 nginx / Caddy）做 TLS 终止，转发到 `<前端端口>`；或在 nginx.conf 里配证书。`CORS_ORIGINS` 改成 `https://<域名>`。NAS 注册链接会自动用当前请求的 `Origin`/`Referer` 头（https 也会正确）。

---

## 10. 文件清单

部署目录最终应有：

```
部署目录/
├── docker-compose.prod.example.yml   # 模板（入库；cp 为下面真实文件后填值）
└── docker-compose.prod.yml           # 真实编排（gitignore 不入库；填好真实值）
```

镜像无需本地构建--全部从 `10.13.66.18:5002/nexcompute/{backend,frontend}` 拉取。
