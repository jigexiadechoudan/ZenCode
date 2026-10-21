@echo off
setlocal
chcp 65001 >nul
if not defined JAVA_HOME (
  echo JAVA_HOME must point to Java 21 or newer >&2
  exit /b 2
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME must point to Java 21 >&2
  exit /b 2
)
for /f "tokens=3" %%V in ('"%JAVA_HOME%\bin\java.exe" -version 2^>^&1') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~V"
for /f "tokens=1 delims=." %%V in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%V"
if not defined JAVA_MAJOR set "JAVA_MAJOR=0"
if %JAVA_MAJOR% LSS 21 (
  echo JAVA_HOME must point to Java 21 or newer >&2
  exit /b 2
)
if not exist "%~dp0target\zencode-0.1.0.jar" (
  echo Packaged JAR is missing. Run start-zencode.cmd or mvn -DskipTests package. >&2
  exit /b 2
)
"%JAVA_HOME%\bin\java.exe" -jar "%~dp0target\zencode-0.1.0.jar" %*
