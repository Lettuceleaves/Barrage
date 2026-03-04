#!/bin/bash
# Barrage API 服务启动脚本

set -e

cd "$(dirname "$0")"

echo "=== 启动 Barrage API 服务 ==="

# 检查端口是否被占用
is_port_in_use() {
    local port="$1"

    if command -v lsof >/dev/null 2>&1; then
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            return 0
        fi
    fi

    if command -v ss >/dev/null 2>&1; then
        if ss -ltn "sport = :$port" 2>/dev/null | awk 'NR > 1 { found = 1 } END { exit found ? 0 : 1 }'; then
            return 0
        fi
    fi

    if command -v netstat >/dev/null 2>&1; then
        if netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]$port$"; then
            return 0
        fi
    fi

    return 1
}

print_port_usage() {
    local port="$1"

    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$port" -sTCP:LISTEN || true
        return
    fi

    if command -v ss >/dev/null 2>&1; then
        ss -ltnp "sport = :$port" 2>/dev/null || true
        return
    fi

    if command -v netstat >/dev/null 2>&1; then
        netstat -ltnp 2>/dev/null | grep -E "[:.]$port[[:space:]]" || true
        return
    fi

    echo "无法获取端口占用详情（未找到 lsof/ss/netstat）"
}

# 检查 jar 文件是否存在
if [ ! -f "barrage-api/target/barrage-api-0.0.1-SNAPSHOT.jar" ]; then
    echo "错误: 找不到 barrage-api jar 文件"
    echo "请先运行: mvn package -DskipTests"
    exit 1
fi

# 默认端口
PORT=${1:-9090}

echo ">>> 启动端口: $PORT"
echo ">>> 日志文件: api.log"

if is_port_in_use "$PORT"; then
    echo ">>> 端口 $PORT 已被占用，无法启动 API 服务"
    echo ">>> 端口占用详情:"
    print_port_usage "$PORT"
    echo ">>> 如需停止现有服务，请运行: ./stop-all.sh"
    exit 1
fi

# 启动 API 服务
java --enable-native-access=ALL-UNNAMED \
    -jar barrage-api/target/barrage-api-0.0.1-SNAPSHOT.jar \
    $PORT > api.log 2>&1 &

API_PID=$!
echo ">>> API 进程已拉起 (PID: $API_PID)，正在检查服务状态..."

# 等待服务启动
sleep 3

# 检查服务是否正常运行
if ps -p $API_PID > /dev/null; then
    echo ">>> API 服务启动成功并运行正常"
    echo ">>> 访问地址: http://localhost:$PORT/api/control/status"
    tail -10 api.log
else
    echo ">>> API 服务启动失败，查看日志:"
    cat api.log
    exit 1
fi
