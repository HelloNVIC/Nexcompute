# 管理端后端 Dockerfile（任务：管理端打包 Docker）
# 多阶段构建：Gradle 编译 -> JRE 运行时

# ---- 构建阶段 ----
FROM gradle:9.6.1-jdk21 AS builder

WORKDIR /app

# 先复制构建文件以利用 Docker 缓存
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gradlew gradlew.bat ./

# 下载依赖（利用缓存层）
RUN gradle dependencies --no-daemon || true

# 复制源码并构建
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- 运行时阶段 ----
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Nexcompute"

WORKDIR /app

# 安装必要工具
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 复制构建产物
COPY --from=builder /app/build/libs/*.jar app.jar

# 创建数据目录（文件存储）
RUN mkdir -p /app/data/storage/images /app/data/storage/public-images /app/data/storage/migration /app/data/storage/tmp

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=60s \
    CMD curl -f http://localhost:8080/api/ping || exit 1

EXPOSE 8080

# JVM 参数：容器感知内存限制
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
