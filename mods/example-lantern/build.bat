@echo off
REM Builds mods/example-lantern/glow-lantern.jar against the compiled game.
REM Run build-fast.bat (or mvn compile) first so target\classes exists.
setlocal
cd /d "%~dp0"

set "CP=..\..\target\classes"
if not exist "%CP%" (
  echo [mod] %CP% not found - build the game first ^(build-fast.bat^).
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes
mkdir build\classes\META-INF\services

javac -cp "%CP%" -d build\classes src\com\example\lantern\LanternMod.java
if errorlevel 1 exit /b 1

copy /y "src\META-INF\services\com.voxel.api.VoxelMod" "build\classes\META-INF\services\" >nul
xcopy /e /i /y assets build\classes\assets >nul

jar cf glow-lantern.jar -C build\classes .
if errorlevel 1 exit /b 1

echo [mod] built %~dp0glow-lantern.jar
echo [mod] copy it into the game's mods\ folder to load it.
