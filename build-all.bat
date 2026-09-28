@echo off
REM ============================================================
REM  AIBot Fabric multi-version build script (Windows)
REM
REM  NOTE: This file is intentionally ASCII-only.
REM  cmd.exe reads .bat files using the system ANSI codepage,
REM  so non-ASCII (e.g. Chinese) comments can break execution.
REM
REM  EDIT THE THREE JDK PATHS BELOW to match your machine.
REM ============================================================
setlocal enabledelayedexpansion

REM JDK 21: used to LAUNCH Gradle for the 1.20.1 / 1.21.1 / 1.21.11 lines.
REM         (Loom 1.17.x itself requires a Java 21+ JVM to run.)
set "AIBOT_JDK21=C:\Users\zjh19\DSH\tools\jdk-21.0.12.1+1"

REM JDK 25: required by the 26.3 line (Minecraft 26.3 needs Java 25).
set "AIBOT_JDK25=C:\Users\zjh19\DSH\tools\jdk-25.0.4.1+1"

REM JDK 17: compile toolchain for 1.20.1 only (NOT used to launch Gradle).
REM         Its path is also registered in fab-1.20.1\gradle.properties.
set "AIBOT_JDK17=C:\Users\zjh19\DSH\tools\jdk-17.0.20.1+1"

if not exist "%AIBOT_JDK17%\bin\java.exe" (
  echo [WARN] JDK 17 not found: %AIBOT_JDK17%
  echo        The 1.20.1 build may fail. Fix AIBOT_JDK17 here and in
  echo        fab-1.20.1\gradle.properties ^(org.gradle.java.installations.paths^).
)
if not exist "%AIBOT_JDK21%\bin\java.exe" (
  echo [ERROR] JDK 21 not found: %AIBOT_JDK21%
  echo         Please edit AIBOT_JDK21 at the top of this script.
  exit /b 1
)
if not exist "%AIBOT_JDK25%\bin\java.exe" (
  echo [ERROR] JDK 25 not found: %AIBOT_JDK25%
  echo         Please edit AIBOT_JDK25 at the top of this script.
  exit /b 1
)

echo ============================================================
echo  AIBot build: 1.20.1 / 1.21.1 / 1.21.11 / 26.3
echo ============================================================

set "ROOT=%~dp0"
set "FAILED=0"

for %%V in (fab-1.20.1 fab-1.21.1 fab-1.21.11) do (
  echo.
  echo ------------------------------------------------------------
  echo  [%%V] building with JDK 21 ...
  echo ------------------------------------------------------------
  set "JAVA_HOME=%AIBOT_JDK21%"
  pushd "%ROOT%%%V"
  call gradlew.bat build --no-daemon
  if errorlevel 1 (
    echo [FAIL] %%V
    set "FAILED=1"
  ) else (
    echo [OK]   %%V
  )
  popd
)

echo.
echo ------------------------------------------------------------
echo  [fab-26.3] building with JDK 25 ...
echo ------------------------------------------------------------
set "JAVA_HOME=%AIBOT_JDK25%"
pushd "%ROOT%fab-26.3"
call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo [FAIL] fab-26.3
  set "FAILED=1"
) else (
  echo [OK]   fab-26.3
)
popd

echo.
echo ============================================================
if "%FAILED%"=="1" (
  echo  RESULT: some versions FAILED. See the log above.
  exit /b 1
) else (
  echo  RESULT: all four versions built successfully.
  echo  Artifacts: fab-*\build\libs\aibot-0.1.0.jar
)
echo ============================================================
endlocal
