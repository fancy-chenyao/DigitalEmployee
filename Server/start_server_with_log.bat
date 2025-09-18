@echo off
chcp 65001 >nul
title MobileGPT Server with Full Logging

echo.
echo ========================================
echo    MobileGPT 服务器 - 完整日志版本
echo ========================================
echo.

cd /d "%~dp0"

echo 🔧 检查Python环境...
python --version
if errorlevel 1 (
    echo ❌ Python未安装或未添加到PATH
    pause
    exit /b 1
)

echo.
echo 🚀 启动服务器（完整日志模式）...
echo 📝 所有输出将保存到 logs 目录
echo 💡 按 Ctrl+C 停止服务器
echo.

python start_server_with_full_log.py

echo.
echo 🔚 服务器已关闭
pause