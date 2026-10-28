@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo  lizl-java-data-analyzer 一键 Release
echo ==========================================
echo.

if not exist "%~dp0tools\release.ps1" (
  echo [错误] 未找到 tools\release.ps1
  pause
  exit /b 1
)

where powershell >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 PowerShell，无法执行发布流程
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\release.ps1" %*
set "CODE=%ERRORLEVEL%"

echo.
if "%CODE%"=="2" (
  echo [取消] 已在确认步骤中止，未产生任何远端变更。
  pause
  exit /b 0
)

if not "%CODE%"=="0" (
  echo [失败] 流程已中断，退出码 %CODE%。请按上方报告处理后重跑。
  pause
  exit /b %CODE%
)

echo [完成] 发布成功。
pause
exit /b 0
