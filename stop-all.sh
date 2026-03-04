#!/bin/bash
# Barrage 停止所有服务脚本

cd "$(dirname "$0")"

echo "=== 停止 Barrage 所有服务 ==="

# 停止 API 服务
echo ">>> 停止 API 服务..."
pkill -f "barrage-api-0.0.1-SNAPSHOT.jar" && echo "API 服务已停止" || echo "未找到运行中的 API 服务"

# 停止 Echo 服务
echo ">>> 停止 Echo 服务..."
pkill -f "simple_http_echo_server.py" && echo "Echo 服务已停止" || echo "未找到运行中的 Echo 服务"

# 停止前端服务
echo ">>> 停止前端服务..."
pkill -f "vite" && echo "前端服务已停止" || echo "未找到运行中的前端服务"

echo ""
echo "✅ 所有服务已停止"
