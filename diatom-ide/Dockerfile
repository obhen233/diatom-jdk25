# 使用 Eclipse Temurin 的 JDK 25 Alpine 版镜像
FROM eclipse-temurin:25-jre-alpine

# 设置时区（可选）
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# ---- 创建非 root 用户 ----
# -D 表示不设置密码（默认禁用密码登录），-s 指定默认 shell
RUN adduser -D -s /bin/sh appuser

# 设置工作目录
WORKDIR /app

# 先将配置文件复制到容器
COPY --chown=appuser:appuser application.properties /app/application.properties

# 将本地的 jar 包复制到容器，并更改其所有者为 appuser
COPY --chown=appuser:appuser diatom-ide-0.0.1-SNAPSHOT.jar /app/diatom-ide-0.0.1-SNAPSHOT.jar

# 确保 /app 目录本身对 appuser 可写（关键一步）
RUN chmod -R 755 /app && chown -R appuser:appuser /app

# 暴露应用端口
EXPOSE 8080

# 设置 JVM 参数（可根据需要调整）
# 注意：不设置固定 -Xmx，堆上限由 ENTRYPOINT 中的 -XX:MaxRAMPercentage=75.0 容器感知决定（§5.3）
ENV JAVA_OPTS="-Xms256m"

# Docker 认证配置（可在 docker run 时通过 -e 参数覆盖）
# 建议使用 password-hash 代替明文密码
ENV IDE_DOCKER_ENABLED=auto
ENV IDE_DOCKER_USERNAME=appuser
# 通过 docker run -e IDE_DOCKER_PASSWORD=<password> 设置，不设默认密码
ENV IDE_DOCKER_PASSWORD=
ENV IDE_DOCKER_PASSWORD_HASH=

# 切换到非 root 用户
USER appuser

# 健康检查（Actuator 探针，Docker HEALTHCHECK 不携带 token，已由 AuthFilter 放行 /actuator/health）
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# 启动 Spring Boot 应用（JDK 25：ZGC 低延迟 GC + 容器感知堆 + CDS 共享存档加速启动）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/tmp/diatom-ide.jsa -jar /app/diatom-ide-0.0.1-SNAPSHOT.jar"]