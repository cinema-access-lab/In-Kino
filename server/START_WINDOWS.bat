@echo off
rem SPDX-License-Identifier: Apache-2.0
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  py -m venv .venv || (pause & exit /b 1)
  .venv\Scripts\python.exe -m pip install -r requirements.txt || (pause & exit /b 1)
)
if "%INKINO_ADMIN_TOKEN%"=="" (
 echo ERROR: Set INKINO_ADMIN_TOKEN to a long random secret in this terminal first.
 pause
 exit /b 1
)
.venv\Scripts\python.exe -m uvicorn app:app --host 0.0.0.0 --port 8765
if errorlevel 1 pause
