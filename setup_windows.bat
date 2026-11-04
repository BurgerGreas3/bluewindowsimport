@echo off
echo [*] Installing required Python dependencies for Windows...
python -m pip install --upgrade pip beautifulsoup4 lxml pillow requests python-dotenv
echo.
echo [*] Done! You can now run:
echo     blue.bat
echo or
echo     blue.bat --bg-plus
pause
