#!/bin/bash

# --- 配置区 ---
APP_NAME="barrage"
DEPLOY_DIR="./barrage-deploy"
JAR_PATTERN="barrage-cli/target/barrage-cli-*.jar"
IMAGE_NAME="barrage-app:latest"

# 颜色定义
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
NC='\033[0m'

usage() {
    echo -e "${YELLOW}Usage: $0 [-c] [-r]${NC}"
    echo "  -c  Compile: mvn package (with native profile) and copy JAR"
    echo "  -r  Run: Build Docker image and start container"
    exit 1
}

# 参数解析
COMPILE=false
RUN=false
while getopts "cr" opt; do
    case $opt in
        c) COMPILE=true ;;
        r) RUN=true ;;
        *) usage ;;
    esac
done

[[ "$COMPILE" == false && "$RUN" == false ]] && usage

# --- 1. 编译阶段 ---
if [ "$COMPILE" = true ]; then
    # 【改动点】添加 -Pnative 激活 shade 插件，确保写入 Main-Class
    echo -e "${GREEN}>>> Executing: mvn clean package -Pnative...${NC}"
    mvn clean package -Pnative -DskipTests

    if [ $? -eq 0 ]; then
        SOURCE_JAR=$(ls $JAR_PATTERN 2>/dev/null | grep -vE "javadoc|sources" | head -n 1)

        if [ -z "$SOURCE_JAR" ]; then
            echo -e "${RED}>>> Error: Cannot find JAR in $JAR_PATTERN${NC}"
            exit 1
        fi

        mkdir -p "$DEPLOY_DIR"
        cp "$SOURCE_JAR" "$DEPLOY_DIR/app.jar"
        echo -e "${GREEN}>>> Success: $SOURCE_JAR -> $DEPLOY_DIR/app.jar${NC}"
    else
        echo -e "${RED}>>> Maven Build Failed!${NC}"
        exit 1
    fi
fi

# --- 2. 运行阶段 ---
if [ "$RUN" = true ]; then
    echo -e "${GREEN}>>> Building Docker Image: $IMAGE_NAME...${NC}"

    if [ ! -f "$DEPLOY_DIR/app.jar" ]; then
        echo -e "${RED}>>> Error: $DEPLOY_DIR/app.jar not found. Run with -c first.${NC}"
        exit 1
    fi

    # 修复路径问题：建议先进入目录构建，确保上下文正确
    (cd "$DEPLOY_DIR" && docker build -t "$IMAGE_NAME" .)

    echo -e "${GREEN}>>> Restarting Container...${NC}"
    docker rm -f "$APP_NAME" 2>/dev/null || true

    # 【改动点】如果是交互式 CLI，建议加上 -it 否则看不到菜单
    # 如果只是后台服务，保留 -d
    echo -e "${GREEN}>>> Launching Container...${NC}"
    docker run -it --rm \
        --name "$APP_NAME" \
        -p 8080-8085:8080-8085 \
        "$IMAGE_NAME"

    # 注意：使用 -it --rm 后，脚本会阻塞直到你退出应用
fi