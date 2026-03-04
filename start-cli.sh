#!/bin/bash
# Barrage CLI 启动脚本

set -e

cd "$(dirname "$0")"

echo "=== 启动 Barrage CLI ==="

# 构建 classpath
echo ">>> 构建 classpath..."

# 项目模块
CLASSPATH="barrage-cli/target/barrage-cli-0.0.1-SNAPSHOT.jar"
CLASSPATH="$CLASSPATH:barrage-engine/target/barrage-engine-0.0.1-SNAPSHOT.jar"
CLASSPATH="$CLASSPATH:barrage-kernel/target/barrage-kernel-0.0.1-SNAPSHOT.jar"
CLASSPATH="$CLASSPATH:barrage-protocol/target/barrage-protocol-0.0.1-SNAPSHOT.jar"

# Maven 本地仓库路径
M2_REPO="$HOME/.m2/repository"

# Jackson 依赖
CLASSPATH="$CLASSPATH:$M2_REPO/com/fasterxml/jackson/core/jackson-databind/2.17.0/jackson-databind-2.17.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/com/fasterxml/jackson/core/jackson-core/2.17.0/jackson-core-2.17.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/com/fasterxml/jackson/core/jackson-annotations/2.17.0/jackson-annotations-2.17.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/com/fasterxml/jackson/dataformat/jackson-dataformat-toml/2.17.0/jackson-dataformat-toml-2.17.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.17.0/jackson-dataformat-yaml-2.17.0.jar"

# YAML 依赖
CLASSPATH="$CLASSPATH:$M2_REPO/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"

# SpotBugs 注解
CLASSPATH="$CLASSPATH:$M2_REPO/com/github/spotbugs/spotbugs-annotations/4.8.6/spotbugs-annotations-4.8.6.jar"

# 检查关键文件是否存在
if [ ! -f "barrage-cli/target/barrage-cli-0.0.1-SNAPSHOT.jar" ]; then
    echo "❌ 错误: 找不到 barrage-cli jar 文件"
    echo "请先运行: mvn package -DskipTests -Dmaven.test.skip=true"
    exit 1
fi

# 启动 CLI
echo ">>> 启动 Barrage CLI..."
echo ""

java --enable-native-access=ALL-UNNAMED \
    -cp "$CLASSPATH" \
    com.barrage.cli.Main
