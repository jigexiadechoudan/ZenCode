@echo off
setlocal
chcp 65001 >nul

rem Every run requests a session key; credentials are never stored in the config.

where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven was not found in PATH. Install Maven or run zencode.cmd with a packaged JAR.
  pause
  exit /b 2
)

pushd "%~dp0"
call mvn -q -Dmaven.test.skip=true package
if errorlevel 1 (
  echo Packaging failed. ZenCode was not started.
  popd
  pause
  exit /b 1
)
call "%~dp0zencode.cmd" --config "%~dp0config.local.yaml" %*
set "ZENCODE_EXIT=%ERRORLEVEL%"
popd
if not "%ZENCODE_EXIT%"=="0" pause
exit /b %ZENCODE_EXIT%
