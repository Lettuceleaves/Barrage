#!/bin/bash
# Barrage 前端开发服务器启动脚本

set -e

cd "$(dirname "$0")/barrage-page"

echo "=== 启动 Barrage 前端开发服务器 ==="

# 检查 node_modules 是否存在
if [ ! -d "node_modules" ]; then
    echo ">>> 首次运行，正在安装依赖..."
    npm install
fi

echo ">>> 启动开发服务器..."
echo ">>> 日志文件: ../frontend.log"

# 启动前端服务
npm run dev > ../frontend.log 2>&1 &

FRONTEND_PID=$!
echo ">>> 前端服务已启动 (PID: $FRONTEND_PID)"

# 等待服务启动
sleep 5

# 检查服务是否正常运行
if ps -p $FRONTEND_PID > /dev/null; then
    echo ">>> 前端服务运行正常"
    echo ""
    tail -10 ../frontend.log
else
    echo ">>> 前端服务启动失败，查看日志:"
    cat ../frontend.log
    exit 1
fi
