# 第一阶段：提取 JDK 25 (GraalVM)
FROM ghcr.io/graalvm/native-image-community:25 AS jdk-source

# 第二阶段：构建开发环境
FROM debian:bookworm

# 避免交互式弹窗
ENV DEBIAN_FRONTEND=noninteractive

# 1. 安装基础工具、网络工具及 IDE Server 必须的依赖库
# 重点新增: libatomic1, libkrb5-3, zlib1g (防止 Server 进程崩溃)
# 重点新增: socat (修复端口转发失败)
# 重点新增: locales (防止字符集报错)
RUN apt-get update && apt-get install -y --no-install-recommends \
    # 基础构建工具
    build-essential libz-dev liburing-dev \
    # IDE Server 核心运行库 (修复 1006/127 的关键)
    libatomic1 libkrb5-3 libgssapi-krb5-2 zlib1g libicu-dev libssl-dev \
    # 网络通信与转发
    socat net-tools iproute2 curl wget ca-certificates \
    # 基础环境支撑
    debianutils procps hostname tar gzip git locales \
    # 常用工具
    vim htop jq \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 1.1 生成 UTF-8 语言环境 (防止 Shell 脚本因字符集报错)
RUN echo "en_US.UTF-8 UTF-8" > /etc/locale.gen && \
    locale-gen && \
    update-locale LANG=en_US.UTF-8
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

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

# 4. 设置持久化目录
RUN mkdir -p /root/.barrage

# 5. 环境变量
ENV JAVA_HOME=/opt/graalvm-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace