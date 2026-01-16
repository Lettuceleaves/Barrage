# 第一阶段：提取 JRE (使用完整版镜像以获取 jmods)
FROM ghcr.io/graalvm/native-image-community:25 AS jlink-builder

# 执行 jlink 提取最小运行时
# 删除了 --compress 参数以确保在所有 JDK 版本上都能通过
RUN jlink \
    --add-modules java.base,java.logging,java.desktop,java.management,jdk.unsupported,jdk.charsets,java.naming,java.sql \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --output /opt/runtime-min

# 第二阶段：生产镜像 (使用 Debian Slim 确保 glibc 兼容性)
FROM debian:bookworm-slim

ENV JAVA_HOME=/opt/java \
    PATH="/opt/java/bin:${PATH}" \
    LANG=C.UTF-8

# 安装 liburing2 (io_uring) 和 CA 证书
RUN apt-get update && apt-get install -y --no-install-recommends \
    liburing2 \
    ca-certificates \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 从第一阶段拷贝定制的运行时
COPY --from=jlink-builder /opt/runtime-min /opt/java

WORKDIR /app

# 拷贝应用文件
COPY app.jar path.toml ./
# 如果没有 config 文件夹，先创建它以防报错
RUN mkdir -p config
COPY config ./config

EXPOSE 8080-8180

# 启动
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]