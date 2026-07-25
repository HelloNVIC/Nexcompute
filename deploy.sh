#!/bin/bash
# Nexcompute 管理端一键构建部署脚本
# 1. 本地构建后端 JAR（避开 Docker 内网络问题）
# 2. 构建前端 Docker 镜像
# 3. docker-compose 启动全栈

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========== Nexcompute 管理端部署 =========="
echo ""

# 检查 Gradle
if [ -z "$(which gradle 2>/dev/null)" ] && [ -z "$(ls gradle/wrapper/gradle-wrapper.jar 2>/dev/null)" ]; then
    echo "✗ 未找到 Gradle，请先安装或使用 gradlew"
    exit 1
fi

# 1. 构建后端 JAR
echo "=== [1/4] 构建后端 JAR ==="
cd management-backend
if [ -n "$(which gradle 2>/dev/null)" ]; then
    gradle bootJar --no-daemon -x test
else
    ./gradlew bootJar -x test
fi
cp build/libs/management-backend-0.0.1-SNAPSHOT.jar app.jar
echo "✓ JAR 构建完成: $(ls -lh app.jar | awk '{print $5}')"
cd "$SCRIPT_DIR"

# 2. 构建前端（npm 需要在本地或 Docker 内执行，这里用 Docker 构建）
echo ""
echo "=== [2/4] 构建前端 Docker 镜像 ==="
docker build -t nexcompute-frontend:latest -f management-frontend/Dockerfile management-frontend/
echo "✓ 前端镜像构建完成"

# 3. 构建后端 Docker 镜像（用预编译 JAR）
echo ""
echo "=== [3/4] 构建后端 Docker 镜像 ==="
docker build -t nexcompute-backend:latest -f management-backend/Dockerfile.prebuilt management-backend/
echo "✓ 后端镜像构建完成"

# 4. 启动全栈
echo ""
echo "=== [4/4] 启动 Docker Compose ==="
docker compose up -d
echo ""

# 等待健康检查
echo "等待服务启动..."
sleep 10
for i in $(seq 1 12); do
    if curl -sf http://localhost:8080/api/ping >/dev/null 2>&1; then
        echo "✓ 后端就绪"
        break
    fi
    sleep 5
done

echo ""
echo "========== 部署完成 =========="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep nexcompute
echo ""
echo "前端: http://localhost"
echo "后端 API: http://localhost:8080/api"
echo "管理员: admin / admin123"
