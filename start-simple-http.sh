#!/bin/bash
# 简易 HTTP Echo 服务启动脚本

set -e

cd "$(dirname "$0")"

echo "=== 启动简易 HTTP Echo 服务 ==="

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

if [ ! -f "simple_http_echo_server.py" ]; then
    echo "错误: 找不到 simple_http_echo_server.py"
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "错误: 未找到 python3"
    exit 1
fi

PORT=${1:-8080}
HOST=${2:-0.0.0.0}
LOG_FILE="simple_http_echo_server.log"

echo ">>> 监听地址: $HOST:$PORT"
echo ">>> 日志文件: $LOG_FILE"

if is_port_in_use "$PORT"; then
    echo ">>> 端口 $PORT 已被占用，无法启动服务"
    echo ">>> 端口占用详情:"
    print_port_usage "$PORT"
    exit 1
fi

python3 -u simple_http_echo_server.py --host "$HOST" --port "$PORT" > "$LOG_FILE" 2>&1 &

SERVER_PID=$!
echo ">>> 服务进程已拉起 (PID: $SERVER_PID)，正在检查状态..."

sleep 1

if ps -p "$SERVER_PID" >/dev/null 2>&1; then
    echo ">>> 服务启动成功"
    echo ">>> 访问地址: http://127.0.0.1:$PORT"
    tail -10 "$LOG_FILE" || true
else
    echo ">>> 服务启动失败，查看日志:"
    cat "$LOG_FILE"
    exit 1
fi
