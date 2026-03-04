#!/bin/bash
set -euo pipefail

# ============================================================
# Barrage 打包脚本
# 构建前端 + Native Binary，组装为自包含 tar.gz
# ============================================================

DIST_NAME="barrage-1.0"
DIST_DIR="build/${DIST_NAME}"

echo "=========================================="
echo "  Barrage 打包脚本"
echo "=========================================="

# 0. 清理旧构建产物
rm -rf build/
mkdir -p "${DIST_DIR}"

# 1. 构建前端
echo ""
echo ">>> [1/4] 构建前端 (barrage-page)..."
cd barrage-page
npm install
npx vite build
cd ..

# 2. Maven 编译全部模块
echo ""
echo ">>> [2/4] Maven 编译全部模块..."
mvn clean install -DskipTests

# 3. Native Image 编译
echo ""
echo ">>> [3/4] GraalVM Native Image 编译..."
mvn -pl barrage-api -P native native:compile -DskipTests

# 4. 组装发布目录
echo ""
echo ">>> [4/4] 组装发布包..."

# 复制 native binary
cp barrage-api/target/barrage-api "${DIST_DIR}/barrage-api"
chmod +x "${DIST_DIR}/barrage-api"

# 复制前端构建产物
cp -r barrage-page/dist/ "${DIST_DIR}/www/"

# 复制配置文件
cp -r config/ "${DIST_DIR}/config/"

# 复制引导文件 path.toml
cp path.toml "${DIST_DIR}/path.toml"

# 生成启动脚本
cat > "${DIST_DIR}/start.sh" << 'SCRIPT'
#!/bin/bash
cd "$(dirname "$0")"
./barrage-api "$@"
SCRIPT
chmod +x "${DIST_DIR}/start.sh"

# 打包 tar.gz
cd build/
tar -czf "${DIST_NAME}.tar.gz" "${DIST_NAME}/"
cd ..

echo ""
echo "=========================================="
echo "  打包完成!"
echo "  产物: build/${DIST_NAME}.tar.gz"
echo ""
echo "  使用方式:"
echo "    tar -xzf build/${DIST_NAME}.tar.gz"
echo "    cd ${DIST_NAME}"
echo "    ./start.sh"
echo "=========================================="
