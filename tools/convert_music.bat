@echo off
REM ---------------------------------------------------------------------------
REM Batch-convert a folder of uncompressed audio (WAV/FLAC/AIFF/M4A/OGG) into
REM the mono MP3 playlist the game plays from src\main\resources\music.
REM
REM   usage:  tools\convert_music.bat <input-dir> [output-dir]
REM
REM The game only needs one speaker, so everything is downmixed to mono (-ac 1)
REM and compressed to 96 kbps MP3 -- a ~4 GB WAV collection lands at roughly a
REM tenth of the size with no audible loss for game ambience.
REM
REM Requires ffmpeg on PATH (https://ffmpeg.org). Example:
REM   tools\convert_music.bat D:\ripped\MCSM D:\game-music
REM   copy D:\game-music\*.mp3 src\main\resources\music\
REM ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion

if "%~1"=="" goto usage
set "SRC=%~1"
set "OUT=%~2"
if "%OUT%"=="" set "OUT=src\main\resources\music"

where ffmpeg >nul 2>&1 || (echo ffmpeg not found on PATH - install it first ^(https://ffmpeg.org^) & exit /b 1)
if not exist "%SRC%\" (echo input directory not found: %SRC% & exit /b 1)
if not exist "%OUT%\" mkdir "%OUT%"

set "COUNT=0"
set "SKIPPED=0"
for %%E in (wav flac m4a aiff aif ogg) do (
    for /r "%SRC%" %%F in (*.%%E) do (
        set "OUTFILE=%OUT%\%%~nF.mp3"
        if exist "!OUTFILE!" (
            set /a SKIPPED+=1
        ) else (
            ffmpeg -hide_banner -loglevel error -y -i "%%~fF" -ac 1 -ar 44100 -codec:a libmp3lame -b:a 96k "!OUTFILE!"
            if errorlevel 1 (
                echo failed: %%~nxF
            ) else (
                for %%S in ("!OUTFILE!") do set /a "KB=%%~zS/1024"
                echo converted: %%~nF.mp3 ^(!KB! KB^)
                set /a COUNT+=1
            )
        )
    )
)

echo done: !COUNT! converted, !SKIPPED! already present -^> %OUT%
exit /b 0

:usage
echo usage: tools\convert_music.bat ^<input-dir^> [output-dir]
exit /b 2
