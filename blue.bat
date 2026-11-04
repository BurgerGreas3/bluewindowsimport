@echo off
setlocal
set "DIR=%~dp0"
if "%~1"=="--bg" (
    start "" /B pythonw "%DIR%blue_master.py" --daemon > "%TEMP%\blue_daemon.log" 2>&1
    echo [+] Blue background daemon started.
    exit /b 0
)
if "%~1"=="--bg-plus" (
    start "" /B pythonw "%DIR%blue_master.py" --daemon --plus > "%TEMP%\blue_daemon.log" 2>&1
    echo [+] Blue Plus background daemon started.
    exit /b 0
)
if "%~1"=="--stop" (
    taskkill /F /IM pythonw.exe /T >nul 2>&1
    echo [+] Stopped background processes.
    exit /b 0
)
python "%DIR%blue_master.py" %*
