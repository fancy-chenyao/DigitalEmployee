#!/bin/bash

# MobileGPT 服务器启动脚本 - 完整日志版本

echo ""
echo "========================================"
echo "   MobileGPT 服务器 - 完整日志版本"
echo "========================================"
echo ""

# 切换到脚本所在目录
cd "$(dirname "$0")"

echo "🔧 检查Python环境..."
if ! command -v python3 &> /dev/null; then
    if ! command -v python &> /dev/null; then
        echo "❌ Python未安装或未添加到PATH"
        exit 1
    else
        PYTHON_CMD="python"
    fi
else
    PYTHON_CMD="python3"
fi

$PYTHON_CMD --version

echo ""
echo "🚀 启动服务器（完整日志模式）..."
echo "📝 所有输出将保存到 logs 目录"
echo "💡 按 Ctrl+C 停止服务器"
echo ""

# 启动服务器
$PYTHON_CMD start_server_with_full_log.py

echo ""
echo "🔚 服务器已关闭"