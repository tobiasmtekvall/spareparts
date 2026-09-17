@echo off
setlocal EnableExtensions
title Spare Parts Inventory
cd /d "%~dp0"
set "VPY=%~dp0.venv\Scripts\python.exe"
set "LOG=%~dp0start-log.txt"

if exist "%VPY%" goto run

echo.
echo  First run: setting up the Python environment. This takes a minute or two...
echo.
set "PY="
py -3 --version >nul 2>&1 && set "PY=py -3"
if not defined PY python --version >nul 2>&1 && set "PY=python"
if not defined PY goto nopython

%PY% -m venv "%~dp0.venv" > "%LOG%" 2>&1
if errorlevel 1 goto setupfail
"%VPY%" -m pip install --upgrade pip >> "%LOG%" 2>&1
"%VPY%" -m pip install -r "%~dp0requirements.txt" >> "%LOG%" 2>&1
if errorlevel 1 goto setupfail
echo  Setup complete.

:run
start "" "http://localhost:8765"
"%VPY%" "%~dp0server\app.py"
echo.
echo  The server stopped. See the messages above.
pause
exit /b

:nopython
echo  Python was not found.
echo  Install Python 3.10 or newer from https://www.python.org/downloads/
echo  and tick "Add python.exe to PATH" in the installer, then run start.bat again.
echo  Note: if typing "python" opens the Microsoft Store, install from python.org instead.
pause
exit /b 1

:setupfail
echo  Setup failed. Details are in start-log.txt:
type "%LOG%"
if exist "%~dp0.venv" rmdir /s /q "%~dp0.venv"
pause
exit /b 1
