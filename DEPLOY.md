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

## 4. 步骤二：准备 .env 配置

在服务器部署目录（与 `docker-compose.prod.yml` 同目录）创建 `.env`，内容从仓库根目录 `.env.example` 复制后修改：

```bash
# 在服务器上（部署目录）：
cp .env.example .env
vi .env
```

`.env` 必须填的关键项（**值不要加引号**）：

```ini
# TrueNAS REST API
TRUENAS_BASE_URL=http://10.13.66.23        # 改成你的 TrueNAS 地址
TRUENAS_API_KEY=2-xxxxxxxxxxxxxxxx          # TrueNAS API key（须 ACCOUNT_WRITE 角色）
TRUENAS_VERIFY_TLS=false
TRUENAS_TIMEOUT_SECONDS=30
TRUENAS_RETRIES=2
# TRUENAS_USER_HOME_PARENT=/mnt/tank/home   # 需 SSH 登录则设置 home 父目录

# AES-GCM 密码加密密钥（生成：python -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())"）
PASSWORD_ENC_KEY=生成一个32字节base64串=

# NAS 注册页基址（仅回退用；注册链接优先取当前请求地址）
NAS_PORTAL_BASE_URL=http://<服务器IP>:<前端端口>

# pending 过期扫描
NAS_PENDING_EXPIRE_DAYS=7
NAS_EXPIRY_SCAN_INTERVAL_HOURS=6

# SMTP（NAS 开通/拒绝/提交等邮件通知用）
EMAIL_FROM=cufel@cufe.edu.cn
EMAIL_HOST=smtp.exmail.qq.com
EMAIL_PORT=465
EMAIL_PROTOCOL=smtps
EMAIL_USER=cufel@cufe.edu.cn
EMAIL_PASSWD=你的SMTP授权码
EMAIL_BRAND_NAME=合算 Nexcompute
```

> `.env` 中的 `DB_*` / `JWT_SECRET` / `CORS_ORIGINS` 会被 `docker-compose.prod.yml` 的 `environment:` 覆盖（见下一步），所以 DB/JWT/CORS 在 compose 里改即可，`.env` 专注 TrueNAS / AES / SMTP。

---

## 5. 步骤三：编辑 docker-compose.prod.yml

仓库根目录已有 `docker-compose.prod.yml`。复制到服务器后，编辑填入实际值：

```bash
vi docker-compose.prod.yml
```

需替换的占位（全文搜索 `<` 即可定位）：

| 占位 | 说明 | 示例 |
|---|---|---|
| `<PG_HOST>` | 外部 PostgreSQL 主机 | `10.13.66.18` |
| `<PG_PORT>` | PG 端口 | `5432` |
| `<PG_USER>` | PG 用户名 | `nexcompute` |
| `<PG_PASSWORD>` | PG 密码 | `你的强密码` |
| `<服务器IP>` | 部署服务器对外 IP | `192.168.1.50` |
| `<前端端口>` | 前端访问端口 | `80`（或 `8080`） |
| `<改成强随机密钥-至少64字符>` | JWT 签名密钥 | 用 `openssl rand -base64 48` 生成 |

### 端口与地址怎么改

- **前端访问地址** = `http://<服务器IP>:<前端端口>`。
  - 改前端端口：改 `frontend.ports` 左侧，如 `- "8080:80"` → 访问 `http://<IP>:8080`。
- **后端 API**：默认走前端同源（`http://<前端地址>/api/...`），经 nginx 反代到后端容器，**无需对外暴露后端端口**。
  - 如需直连后端 API（受控端直连、调试），取消 `backend.ports` 注释，如 `- "8080:8080"` → 后端 API `http://<IP>:8080/api`。
- **`CORS_ORIGINS`**：填用户浏览器访问前端的地址（同源时也填，保险）。多个逗号分隔，如 `http://192.168.1.50,http://192.168.1.50:8080`。
- **改后端容器端口**（一般不用）：后端容器内固定 `8080`，nginx 反代目标也是 `backend:8080`，无需改动。

### Redis

- 用自带 Redis 容器（默认）：保持 `redis` 服务 + `backend.REDIS_HOST: redis`。
- 用外部 Redis：删掉 `redis` 服务，把 `backend.REDIS_HOST` 改成外部 Redis 地址，`REDIS_PORT` 对应。

---

## 6. 步骤四：配置 Docker 信任私有仓库

私有仓库 `10.13.66.18:5002` 是 HTTP，需让 Docker 信任它（insecure-registries）。

**Linux 服务器**：编辑 `/etc/docker/daemon.json`（没有则新建）：

```json
{
  "insecure-registries": ["10.13.66.18:5002"]
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

**Windows + Docker Desktop**：Settings → Docker Engine → 在 JSON 里加 `"insecure-registries": ["10.13.66.18:5002"]` → Apply & Restart。

---

## 7. 步骤五：拉取镜像并启动

在部署目录（含 `docker-compose.prod.yml` 与 `.env`）执行：

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
`.env` 或 compose 里 `DB_PASSWORD` **加了引号**。Spring 的 properties 解析器**不剥离引号**，`DB_PASSWORD="x"` 会被读成带引号。**值不要加引号**：`DB_PASSWORD=你的密码`（密码含 `@`/`=` 也不要加）。

### Q2：前端能打开但登录提示"网络异常" / `/api` 404
- 确认 `nexcompute-backend` 容器已 `Started`：`docker logs nexcompute-backend`。
- 确认前端与后端在**同一 compose 网络**（`docker-compose.prod.yml` 里两者都在 `nexcompute` 网络）。
- nginx 反代目标是 `http://backend:8080`（service 名 `backend`），勿改 service 名。

### Q3：CORS 报错 / 跨域
`backend.environment.CORS_ORIGINS` 必须包含用户实际访问前端的地址（协议+IP+端口）。如访问 `http://192.168.1.50:8080`，则 `CORS_ORIGINS=http://192.168.1.50:8080`。改后 `docker compose -f docker-compose.prod.yml up -d backend` 重启后端。

### Q4：TrueNAS ping 失败 / `user.create` 401
- `TRUENAS_BASE_URL` 必须后端容器**能访问**到的地址（若 TrueNAS 在内网，确保服务器与 TrueNAS 网络通）。
- `TRUENAS_API_KEY` 须属 ACCOUNT_WRITE 角色（否则 `user.create` 在审批时被拒）。启动 ping 失败**不阻断**启动，仅 WARN，审批时才暴露。

### Q5：如何改后端端口 / 直连后端 API
取消 `docker-compose.prod.yml` 里 `backend.ports` 注释，如 `- "9000:8080"`，则后端 API 直连 `http://<IP>:9000/api`。受控端配置的管理端地址用这个。

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
├── docker-compose.prod.yml   # 生产编排（编辑填值）
├── .env                      # TrueNAS / AES / SMTP 等密钥（gitignore 不入库）
└── （可选）.env.example       # 模板参考
```

镜像无需本地构建——全部从 `10.13.66.18:5002/nexcompute/{backend,frontend}` 拉取。
