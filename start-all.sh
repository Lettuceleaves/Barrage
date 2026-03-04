#!/bin/bash
# Barrage 完整项目启动脚本（API + 前端 + Echo 服务）

set -e

cd "$(dirname "$0")"

echo "=========================================="
echo "   Barrage 项目启动脚本"
echo "=========================================="

# 启动 API 服务
echo ""
echo "[1/3] 启动后端 API 服务..."
./start-api.sh

echo ""
echo "[2/3] 启动简易 HTTP Echo 服务..."
./start-simple-http.sh 8089

echo ""
echo "[3/3] 启动前端开发服务器..."
./start-frontend.sh

echo ""
echo "=========================================="
echo "   ✅ 所有服务启动完成"
echo "=========================================="
echo ""
echo "📡 后端 API: http://localhost:9090/api/control/status"
echo "🌐 前端页面: http://localhost:5173/"
echo "🔌 Echo 服务: http://localhost:8089/"
echo ""
echo "查看日志:"
echo "  - API 日志:       tail -f api.log"
echo "  - Echo 服务日志:  tail -f simple_http_echo_server.log"
echo "  - 前端日志:       tail -f frontend.log"
echo ""
echo "停止服务:"
echo "  - 查看进程: ps aux | grep -E '(barrage-api|vite|simple_http_echo)'"
echo "  - 停止服务: ./stop-all.sh"
echo ""
