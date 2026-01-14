# 第一阶段：提取 JDK 25 (GraalVM)
FROM ghcr.io/graalvm/native-image-community:25 AS jdk-source

# 第二阶段：构建开发环境
FROM debian:bookworm

# 避免交互式弹窗
ENV DEBIAN_FRONTEND=noninteractive

# 1. 安装基础工具及健全的网络测试工具包
RUN apt-get update && apt-get install -y --no-install-recommends \
    debianutils procps hostname wget ca-certificates tar gzip git \
    build-essential libz-dev liburing-dev \
    # 网络测试与诊断工具 \
    iputils-ping net-tools iproute2 curl dnsutils mtr-tiny tcpdump nmap lsof \
    # 常用辅助
    vim htop jq \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 2. 拷贝并配置 JDK
COPY --from=jdk-source /usr/lib64/graalvm /tmp/graalvm_temp
RUN export REAL_PATH=$(find /tmp/graalvm_temp -name java | sed 's/\/bin\/java//' | head -n 1) && \
    mkdir -p /opt/graalvm-25 && \
    cp -r "$REAL_PATH"/* /opt/graalvm-25/ && \
    rm -rf /tmp/graalvm_temp && \
    ln -sf /opt/graalvm-25/bin/* /usr/bin/

# 3. 安装 Maven 3.9.6
RUN wget -qO- https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz | \
    tar -xz -C /usr/local/ && \
    ln -sf /usr/local/apache-maven-3.9.6/bin/mvn /usr/bin/mvn

# 4. 设置持久化目录（用于存放你之前的配置文件）
RUN mkdir -p /root/.barrage

# 5. 环境变量
ENV JAVA_HOME=/opt/graalvm-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace