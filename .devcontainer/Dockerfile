# 第一阶段：获取 JDK 25
FROM ghcr.io/graalvm/native-image-community:25 AS jdk-source

# 第二阶段：构建开发底座
FROM debian:bookworm

# 1. 安装 IDEA 和远程连接必备工具
RUN apt-get update && apt-get install -y --no-install-recommends \
    debianutils procps hostname wget ca-certificates tar gzip git \
    build-essential libz-dev liburing-dev \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 2. 动态拷贝并修复路径
# 先把原镜像中可能的路径全部拷到一个临时目录
COPY --from=jdk-source /usr/lib64/graalvm /tmp/graalvm_temp

# 自动寻找 java 所在目录并移动到 /opt/graalvm-25
RUN export REAL_PATH=$(find /tmp/graalvm_temp -name java | sed 's/\/bin\/java//' | head -n 1) && \
    mkdir -p /opt/graalvm-25 && \
    cp -r "$REAL_PATH"/* /opt/graalvm-25/ && \
    rm -rf /tmp/graalvm_temp

# 3. 安装 Maven
RUN wget -qO- https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz | \
    tar -xz -C /usr/local/ && \
    ln -sf /usr/local/apache-maven-3.9.6/bin/mvn /usr/bin/mvn

# 4. 建立软链接确保全局可用
RUN ln -sf /opt/graalvm-25/bin/* /usr/bin/

# 5. 设置环境变量
ENV JAVA_HOME=/opt/graalvm-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace