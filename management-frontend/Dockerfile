# 管理端前端 Dockerfile（任务：管理端打包 Docker）
# 多阶段构建：npm 构建 -> nginx 静态服务

# ---- 构建阶段 ----
FROM node:22-alpine AS builder

WORKDIR /app

# 先复制依赖文件以利用缓存
COPY package.json package-lock.json* ./
RUN npm install --legacy-peer-deps --registry https://registry.npmmirror.com

# 复制源码并构建
COPY . .
RUN npm run build

# ---- 运行时阶段 ----
FROM nginx:alpine

LABEL maintainer="Nexcompute"

# 复制 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 复制构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

# 时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
