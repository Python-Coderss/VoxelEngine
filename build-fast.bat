@echo off
REM ---------------------------------------------------------------------------
REM Fast incremental build for day-to-day iteration.
REM
REM Why this exists: `mvn compile` re-runs javac over all 300 sources every time
REM (Maven 3.9 prints "Changes detected - recompiling the module! :source" even
REM when the tree is untouched). This script compiles only the sources whose
REM .class is missing or older than the .java, which takes a couple of seconds.
REM
REM Assets are NOT copied: the game loads them from src/main/resources at runtime,
REM so target/classes only needs .class files. `mvn package` still bundles them.
REM
REM Usage:  build-fast.bat          compile changed sources
REM         build-fast.bat test     then run the test suite
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

set "JAVAC=C:\Program Files\Java\jdk-21\bin\javac.exe"
if not exist "%JAVAC%" set "JAVAC=javac"

REM Locate a working maven (one-off classpath dump, and for tests).
set "MVN=mvn"
where mvn >nul 2>&1 || set "MVN=%~dp0mvnw.cmd"
if exist "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11\a2d47e15\bin\mvn.cmd" (
  set "MVN=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11\a2d47e15\bin\mvn.cmd"
)

if not exist "target\cp.txt" (
  echo [build-fast] Building dependency classpath...
  call "!MVN!" -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
  if errorlevel 1 (
    echo [build-fast] Could not resolve dependencies.
    exit /b 1
  )
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\fast_compile.ps1" -Javac "%JAVAC%"
if errorlevel 1 exit /b 1

if /I "%~1"=="test" (
  echo [build-fast] Running tests...
  call "!MVN!" -o -q test
  exit /b %errorlevel%
)
exit /b 0