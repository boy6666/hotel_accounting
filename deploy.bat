@echo off
REM ============================================================
REM 酒店记账系统 - Windows 一键部署/更新脚本
REM 用法：双击运行，或在项目目录执行 deploy.bat
REM 前提：已安装 Docker Desktop（正在运行）和 Git
REM ============================================================
chcp 65001 >nul
cd /d "%~dp0"

echo [1/4] 拉取最新代码...
git pull --ff-only
if errorlevel 1 (
    echo [错误] git pull 失败，请检查网络或本地改动
    pause
    exit /b 1
)

if not exist .env (
    echo [2/4] 首次部署：复制 .env.example 为 .env，请编辑 .env 修改密码！
    copy .env.example .env >nul
    notepad .env
)

echo [3/4] 构建并启动容器（首次需要几分钟）...
docker compose up -d --build
if errorlevel 1 (
    echo [错误] docker compose 失败，请确认 Docker Desktop 正在运行
    pause
    exit /b 1
)

echo [4/4] 当前服务状态：
docker compose ps
echo.
echo 部署完成。访问 http://localhost:%APP_PORT%（默认 8080），默认账号 admin / admin123
echo 查看日志：docker compose logs -f backend
pause
