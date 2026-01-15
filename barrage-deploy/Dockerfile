# 第一阶段：复用开发容器的 JDK 源 (确保版本对齐)
FROM ghcr.io/graalvm/native-image-community:25 AS jdk-source

# 第二阶段：生产运行环境
FROM debian:bookworm-slim

# 1. 设置生产环境变量
# 解决你之前遇到的 Banner 乱码 (UTF-8)
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    DEBIAN_FRONTEND=noninteractive

# 2. 仅安装运行必需的库
# - liburing2: io_uring 运行时库 (解决 liburing.so.2 缺失问题)
# - procps: 提供基本进程查看
RUN apt-get update && apt-get install -y --no-install-recommends \
    liburing2 \
    procps \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 3. 按照你开发容器的逻辑提取 JDK
COPY --from=jdk-source /usr/lib64/graalvm /tmp/graalvm_temp
RUN export REAL_PATH=$(find /tmp/graalvm_temp -name java | sed 's/\/bin\/java//' | head -n 1) && \
    mkdir -p /opt/graalvm-25 && \
    cp -r "$REAL_PATH"/* /opt/graalvm-25/ && \
    rm -rf /tmp/graalvm_temp

# 4. 配置环境变量
ENV JAVA_HOME=/opt/graalvm-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# 5. 应用部署
WORKDIR /app
# 这里的 app.jar 是由 run.sh 脚本从子模块 target 拷贝过来的
COPY app.jar /app/app.jar

# 声明端口
EXPOSE 8080-8180

# 6. 启动命令
# --enable-native-access=ALL-UNNAMED: 必须开启以支持 JDK 25 的 FFM API
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]