# ===================================================================
# CourseSeckill —— 多阶段构建
# ===================================================================
# 为什么要多阶段：
#   第一阶段的 Maven 仓库有几百 MB，如果只写一个 FROM 再 COPY 整个源码，
#   镜像会大到没法看（而且每次改代码都要重新下一遍依赖）。
#   拆成两层之后，依赖层只在 pom.xml 变化时失效，改 Java 代码不会重下依赖。
# ===================================================================

# ---------- 阶段 1：编译 ----------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# 先只拷 pom.xml 单独拉依赖 —— 这一层能被 Docker 缓存住。
# 注意 mvnw 用的是 only-script 模式（没有 wrapper jar），这里直接用镜像自带的 mvn 更省事。
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# 再拷源码编译。tests 跳过：测试依赖 MySQL/Redis/RabbitMQ，
# 在构建镜像的阶段没有这些服务，跑起来必然失败。
# 测试在 CI 里单独跑（见 .github/workflows/ci.yml），职责分开。
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ---------- 阶段 2：运行时 ----------
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# 非 root 运行：容器里用 root 跑 Java 进程是常见的安全债，
# 一条 useradd 就能避免，没有理由不做。
RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build --chown=app:app /build/target/*.jar app.jar

# 容器内监听 8080；对外映射由 compose 决定
EXPOSE 8080

# 时区：镜像默认 UTC，而本项目所有时间字段都用 Asia/Shanghai 写入，
# 不设的话页面上的创建时间会差 8 小时。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"

# 健康检查用 /v3/api-docs：它受 permitAll 保护，不需要 token，
# 且能真正验证 Web 层与 Spring 上下文都起来了（比只看端口通更可靠）。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/v3/api-docs > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
