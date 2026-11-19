@echo off
rem SPDX-License-Identifier: Apache-2.0
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  py -m venv .venv || (pause & exit /b 1)
  .venv\Scripts\python.exe -m pip install -r requirements.txt || (pause & exit /b 1)
)
.venv\Scripts\python.exe gui.py
if errorlevel 1 pause
