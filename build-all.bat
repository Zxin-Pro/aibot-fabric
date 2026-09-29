@echo off
REM ============================================================
REM  AIBot Fabric multi-version build script (Windows)
REM
REM  NOTE: This file is intentionally ASCII-only.
REM  cmd.exe reads .bat files using the system ANSI codepage,
REM  so non-ASCII (e.g. Chinese) comments can break execution.
REM
REM  EDIT THE THREE JDK PATHS BELOW to match your machine.
REM
REM  MEMORY NOTE (important, this is not optional polish):
REM  each module is configured with org.gradle.jvmargs=-Xmx2G. Running the
REM  four builds back-to-back with --no-daemon makes the JVM of build N+1
REM  start before build N's memory has been reclaimed by Windows, and the
REM  new daemon dies with:
REM      "There is insufficient memory for the Java Runtime Environment"
REM      "Gradle build daemon disappeared unexpectedly"
REM  This script therefore (a) REUSES one daemon across modules instead of
REM  tearing down a JVM per module, and (b) pauses SETTLE_SECONDS between
REM  modules. On a 16 GB machine that is the difference between all four
REM  succeeding and two of them failing.
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

REM Set SKIP_CLEAN=1 to do an incremental build. Faster, but see the
REM processResources note further down before you do.
if not defined SKIP_CLEAN set "SKIP_CLEAN=0"

REM Set KEEP_DAEMON=0 to force --no-daemon per module (slower, more memory churn).
if not defined KEEP_DAEMON set "KEEP_DAEMON=1"

REM Seconds to wait between modules so the OS reclaims the previous JVM.
if not defined SETTLE_SECONDS set "SETTLE_SECONDS=5"

REM Heap for the Gradle JVM that runs these builds.
REM
REM WHY THIS EXISTS: the modules' gradle.properties ask for -Xmx2G. On a
REM machine with 16 GB of RAM that is fine for ONE module but not for four
REM in a row -- Windows commits each JVM's reservation up front, so the
REM third or fourth build dies with "insufficient memory" even though the
REM Task Manager still shows several GB "free" (physical free != commit
REM free). Symptom: "Gradle build daemon disappeared unexpectedly".
REM
REM Overriding DOWN to 1G here is safe for this project: it is a small mod
REM with ~24 source files per module, and it has been verified to build
REM clean at 1G. Raise this only if you hit a real OutOfMemoryError during
REM compilation. Set to empty to fall back to each module's own setting.
if not defined AIBOT_GRADLE_HEAP set "AIBOT_GRADLE_HEAP=-Xmx1G"

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
set "BUILT=0"
set "OUTDIR=%ROOT%release"

REM Read the version straight from gradle.properties so this script never
REM goes stale. Hardcoding it here is how it ended up claiming 0.1.0 while
REM the build actually produced 0.5.0.
set "VERSION="
for /f "usebackq tokens=1,2 delims==" %%A in ("%ROOT%fab-1.20.1\gradle.properties") do (
  if /i "%%A"=="version" set "VERSION=%%B"
)
if not defined VERSION (
  echo [ERROR] Could not read "version" from fab-1.20.1\gradle.properties
  exit /b 1
)
echo  Version: %VERSION%
echo  Output : %OUTDIR%
echo.

REM Fresh output dir so jars from older versions never linger.
if exist "%OUTDIR%" rmdir /s /q "%OUTDIR%"
mkdir "%OUTDIR%"

REM First module warms the daemon; the rest reuse it.
set "WARMED=0"

for %%V in (fab-1.20.1 fab-1.20.6 fab-1.21.1 fab-1.21.4 fab-1.21.8 fab-1.21.11) do (
  call :buildOne %%V jdk21 %AIBOT_JDK21%
)
call :buildOne fab-26.1.2 jdk25 %AIBOT_JDK25%
call :buildOne fab-26.3 jdk25 %AIBOT_JDK25%

echo.
echo ============================================================
if "%FAILED%"=="1" (
  echo  RESULT: some versions FAILED. See the log above.
  echo.
  echo  Built OK: %BUILT% of 8.  Any jar that did build is in:
  echo    %OUTDIR%
  echo.
  echo  If the failure was "insufficient memory" or "daemon disappeared",
  echo  close other apps and re-run, or run modules one at a time:
  echo    cd fab-1.20.1 ^&^& gradlew.bat clean build
  exit /b 1
) else (
  echo  RESULT: all four versions built successfully.
  echo.
  echo  Upload-ready jars in %OUTDIR% :
  for %%F in ("%OUTDIR%\*.jar") do echo    %%~nxF
)
echo ============================================================
endlocal
exit /b 0

REM ------------------------------------------------------------
REM  buildOne <module> <jdk-label> <jdk-path>
REM
REM  "clean build" is the default and the clean is NOT just hygiene:
REM  processResources expands ${version} in fabric.mod.json, and Gradle's
REM  incremental resource cache does not reliably notice a version bump.
REM  A plain "build" can package a jar whose FILENAME says 0.5.0 while the
REM  fabric.mod.json inside still says 0.4.0. It loads fine but reports the
REM  wrong version in-game. Opt out with SKIP_CLEAN=1 only if you have not
REM  changed the version since the last clean build.
REM ------------------------------------------------------------
:buildOne
set "MOD=%~1"
set "JDKLABEL=%~2"
set "JDK=%~3"

echo ------------------------------------------------------------
echo  [%MOD%] building ^(%JDKLABEL%^) ...
echo ------------------------------------------------------------
set "JAVA_HOME=%JDK%"

if "%SKIP_CLEAN%"=="1" (
  set "GRADLE_TASK=build"
) else (
  set "GRADLE_TASK=clean build"
)

REM Reuse the daemon after the first module to avoid JVM start/stop churn.
if "%KEEP_DAEMON%"=="0" (
  set "DAEMON_FLAG=--no-daemon"
) else (
  if "%WARMED%"=="0" (
    set "DAEMON_FLAG=--no-daemon"
  ) else (
    set "DAEMON_FLAG="
  )
)

REM Apply the heap override. Passing -Dorg.gradle.jvmargs on the command line
REM wins over the value in gradle.properties, so this also prevents the daemon
REM from being reused across differing JVM args.
if defined AIBOT_GRADLE_HEAP (
  set "HEAP_FLAG=-Dorg.gradle.jvmargs=%AIBOT_GRADLE_HEAP%"
) else (
  set "HEAP_FLAG="
)

pushd "%ROOT%%MOD%"
call gradlew.bat %GRADLE_TASK% %DAEMON_FLAG% %HEAP_FLAG%
set "RC=%errorlevel%"
popd

if not "%RC%"=="0" (
  echo [FAIL] %MOD%  ^(gradle exit %RC%^)
  set "FAILED=1"
  set "WARMED=0"
  goto :eof
)

REM Drop the jar into release\ with the Minecraft version in the name.
set "SRC=%ROOT%%MOD%\build\libs\aibot-%VERSION%.jar"
if not exist "%SRC%" (
  echo [FAIL] %MOD%: expected jar not found:
  echo        %SRC%
  set "FAILED=1"
  goto :eof
)

set "MCVER=%MOD:fab-=%"
copy /y "%SRC%" "%OUTDIR%\aibot-%VERSION%-%MCVER%.jar" >nul
if errorlevel 1 (
  echo [FAIL] %MOD%: could not copy jar into %OUTDIR%
  set "FAILED=1"
) else (
  set /a BUILT+=1
  echo [OK]   %MOD%  -^>  aibot-%VERSION%-%MCVER%.jar
  set "WARMED=1"
  if not "%SETTLE_SECONDS%"=="0" (
    echo        waiting %SETTLE_SECONDS%s for memory to settle ...
    timeout /t %SETTLE_SECONDS% /nobreak >nul
  )
)
goto :eof
