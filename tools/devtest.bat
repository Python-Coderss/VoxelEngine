@echo off
REM ---------------------------------------------------------------------------
REM devtest.bat -- the fast loop. An incremental build+test runner that stays
REM under 5 seconds for the edit -> test cycle it is built for.
REM
REM Why this exists instead of plain Maven: `mvn test` re-runs javac over all
REM ~350 sources plus every plugin check on each invocation (20 s+ even when
REM nothing changed), and `useIncrementalCompilation` never takes effect in
REM this tree. This script compiles only stale files with plain javac and runs
REM JUnit directly, so a no-change run is a file scan + JVM startup only.
REM
REM Maven stays the canonical build (`mvnw -o test` must stay green; CI and
REM packaging use it). This is the iteration loop, with identical flags
REM (-source/-target 1.8, UTF-8, same dependency set).
REM
REM Staleness is tracked with a per-source stamp file recording when javac last
REM compiled it. Mapping a .java to "the" .class does not work here: several
REM sources emit differently-named top-level classes (BetaGenLayers.java emits
REM BetaLayerIsland, BetaWorldGen.java emits more), so a same-name .class check
REM would recompile them on every run and a sweep would delete their output.
REM xcopy /L /D lists exactly the sources newer than their stamp -- the same
REM rule the old .sh enforced with -nt, without needing a shell comparison.
REM
REM Usage:
REM   tools\devtest.bat villager.voice.SpectralAuditTest   :: one test class
REM   tools\devtest.bat SpectralAuditTest ClipIndexTest     :: simple names work
REM   tools\devtest.bat --all                               :: every *Test class
REM   tools\devtest.bat --full SpectralAuditTest            :: force full recompile
REM
REM Resources resolve from src/main/resources (the code's dev fallback checks
REM there first) plus the classloader, so no resource copying step is needed.
REM ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion

REM The classpath separator these JVMs use is ';'; a ':' silently glues the
REM entry onto the last jar and breaks symbol resolution.
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
pushd "%ROOT%" || exit /b 1

set "DEVDIR=target\devloop"
set "CPFILE=%DEVDIR%\cp.txt"
set "MAIN_SRC=src\main\java"
set "TEST_SRC=src\test\java"
set "MAIN_OUT=target\classes"
set "TEST_OUT=target\test-classes"
set "RES_DIR=src\main\resources"

set "FULL=0"
set "RUN_ALL=0"
set "QUERY="
set "FAILED="
set "CHUNK="
set "RESOLVED="

set "T0=%TIME%"

:args
if "%~1"=="" goto args_done
set "ARG=%~1"
shift
if /i "%ARG%"=="--full" (set "FULL=1" & goto args)
if /i "%ARG%"=="--all" (set "RUN_ALL=1" & goto args)
if /i "%ARG%"=="-h" goto usage
if /i "%ARG%"=="--help" goto usage
if /i "%ARG%"=="/?" goto usage
set "QUERY=!QUERY! %ARG%"
goto args

:args_done
if "%RUN_ALL%"=="0" if "%QUERY%"=="" goto usage

where javac >nul 2>&1 || (echo [devtest] javac is not on PATH; install JDK 8 or add it & goto fail)
where java >nul 2>&1 || (echo [devtest] java is not on PATH; install JDK 8 or add it & goto fail)

if not exist "%DEVDIR%\" mkdir "%DEVDIR%"

REM --- dependency classpath (refreshed only when pom.xml changes) -----------
REM xcopy /L /D prints pom.xml only when it is newer than the cached dump.
REM Maven is located by scanning the wrapper's dists cache (the same way
REM build-fast.bat does) so this keeps working when mvnw.cmd's own unpack is
REM broken; if the cache is empty, run mvnw.cmd -v once to populate it.
set "NEEDCP="
if not exist "%CPFILE%" set "NEEDCP=1"
if not defined NEEDCP (
    for /f "delims=" %%A in ('xcopy /L /D /Y "pom.xml" "%CPFILE%" 2^>nul') do if /i "%%A"=="pom.xml" set "NEEDCP=1"
)
if defined NEEDCP (
    echo [devtest] refreshing dependency classpath ^(pom.xml changed^)...
    set "MVN=mvn"
    where mvn >nul 2>&1 || set "MVN=mvnw.cmd"
    for /d %%A in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-*") do for /d %%D in ("%%A\*") do if exist "%%D\bin\mvn.cmd" set "MVN=%%D\bin\mvn.cmd"
    call "!MVN!" -o -q dependency:build-classpath "-Dmdep.outputFile=%CPFILE%" -Dmdep.includeScope=test > "%DEVDIR%\mvn.log" 2>&1
    if errorlevel 1 (
        echo [devtest] FAILED: could not resolve dependencies offline.
        echo   Run 'mvnw.cmd -o test' once to populate the local repository.
        goto fail
    )
)
REM Read with for /f, not `set /p` -- set /p truncates a line at 1024 chars,
REM which silently dropped the last jars from the classpath.
set "DEPCP="
for /f "usebackq delims=" %%L in ("%CPFILE%") do set "DEPCP=%%L"
if not defined DEPCP (
    echo [devtest] FAILED: the dependency classpath file is empty: %CPFILE%
    goto fail
)

REM The output dir itself is on the classpath so a stale file compiles against
REM the already-built classes of everything else (sibling sources included).
set "MAIN_CP=%DEPCP%;%MAIN_OUT%"

REM --- full rebuild ---------------------------------------------------------
if "%FULL%"=="1" (
    echo [devtest] --full: dropping all compiled classes and stamps
    if exist "%MAIN_OUT%\" del /s /q "%MAIN_OUT%\*.class" >nul 2>&1
    if exist "%TEST_OUT%\" del /s /q "%TEST_OUT%\*.class" >nul 2>&1
    if exist "%DEVDIR%\stamps\" rmdir /s /q "%DEVDIR%\stamps"
)

call :compile_tree "%MAIN_SRC%" "%MAIN_OUT%" "%MAIN_CP%" "%DEVDIR%\stamps\main"
if errorlevel 1 goto fail
call :compile_tree "%TEST_SRC%" "%TEST_OUT%" "%MAIN_CP%" "%DEVDIR%\stamps\test"
if errorlevel 1 goto fail

REM --- resolve test classes -------------------------------------------------
set "COUNT=0"
set "T_RUN=%TIME%"
if "%RUN_ALL%"=="1" (
    echo [devtest] running every compiled *Test class
    for /r "%TEST_OUT%" %%F in (*Test.class) do (
        set "NAME=%%~nxF"
        REM Skip inner classes (Outer$InnerTest.class) the way the .sh did.
        if "!NAME:$=!"=="!NAME!" (
            call :class_name "%%~fF"
            set "CHUNK=!CHUNK! !CLASSNAME!"
            set /a COUNT+=1
            REM Keep each java command line well under the 8191-char limit.
            if not "!CHUNK:~6000!"=="" call :run_junit
        )
    )
    call :run_junit
) else (
    for %%T in (!QUERY!) do (
        set "RESOLVED="
        call :resolve_test "%%T"
        if not defined RESOLVED (
            echo [devtest] no compiled test matches '%%T'
            goto fail
        )
        set "CHUNK=!CHUNK! !RESOLVED!"
        set /a COUNT+=1
        if not "!CHUNK:~6000!"=="" call :run_junit
    )
    call :run_junit
)
if "%COUNT%"=="0" (echo [devtest] no tests to run & goto fail)

REM --- report ---------------------------------------------------------------
call :ms "%T0%"
set "STARTMS=%MSVAL%"
call :ms "%T_RUN%"
set /a "SETUPMS=%MSVAL%-STARTMS"
if %SETUPMS% LSS 0 set /a "SETUPMS+=86400000"
set "T_END=%TIME%"
call :ms "%T_END%"
set /a "TOTALMS=%MSVAL%-STARTMS"
if %TOTALMS% LSS 0 set /a "TOTALMS+=86400000"

if "%RUN_ALL%"=="1" (
    REM --all is the whole suite, not the edit->test cycle the budget targets.
    echo [devtest] TOTAL %TOTALMS%ms for the full suite
) else if %SETUPMS% LEQ 5000 (
    echo [devtest] build+start %SETUPMS%ms -- budget OK ^(^<=5s^); total %TOTALMS%ms
) else (
    echo [devtest] build+start %SETUPMS%ms -- OVER the 5s budget; total %TOTALMS%ms
)

popd
if defined FAILED exit /b 1
exit /b 0

REM ---------------------------------------------------------------------------
REM :compile_tree <src_dir> <out_dir> <classpath> <stamp_dir>
REM Compiles the sources that are newer than their stamp, then stamps them.
REM ---------------------------------------------------------------------------
:compile_tree
setlocal EnableDelayedExpansion
set "CSRC=%~1"
set "COUT=%~2"
set "CCP=%~3"
set "CST=%~4"
if not exist "%COUT%\" mkdir "%COUT%"
if not exist "%CST%\" mkdir "%CST%"
if not exist "%DEVDIR%\" mkdir "%DEVDIR%"
set "STALE=%DEVDIR%\stale.txt"
break > "%STALE%"
REM /L lists instead of copying, /D keeps only sources newer than the stamp.
xcopy /L /D /S /Y /I "%CSRC%\*.java" "%CST%\" 2>nul | findstr /i /e /c:".java" > "%STALE%"
REM Counted in batch on purpose: a bare `find` resolves to Git's GNU find when
REM Git's usr\bin is on PATH, which treats "/c" as a path and walks the drive.
set "STALECOUNT=0"
for /f "usebackq delims=" %%S in ("%STALE%") do set /a STALECOUNT+=1
if "%STALECOUNT%"=="0" (
    endlocal & exit /b 0
)
echo [devtest] compiling %STALECOUNT% stale file(s) -^> %COUT%
REM C1-only JIT + shared archive: javac is a short-lived process here, so C2
REM warmup is pure overhead (Main.java: 2.6 s -> 1.3 s).
javac -J-XX:TieredStopAtLevel=1 -J-Xshare:auto -encoding UTF-8 -nowarn -source 1.8 -target 1.8 -g -cp "%CCP%" -d "%COUT%" @"%STALE%" > "%DEVDIR%\javac.log" 2>&1
set "JRC=%ERRORLEVEL%"
findstr /v /i /c:"bootstrap class path" /c:"deprecat" "%DEVDIR%\javac.log"
if not "%JRC%"=="0" (
    echo [devtest] COMPILE FAILED
    endlocal & exit /b 1
)
REM Stamp only what this successful javac actually compiled. The stale list
REM holds repo-relative paths ("src\main\java\..."); strip that prefix so the
REM stamp mirrors the source tree beneath the stamps dir. The stamp keeps the
REM .java name on purpose: xcopy /D only compares a source against the file of
REM the same name, so a .stamp suffix would make every file look uncompiled.
set "PREFIX=%CSRC%\"
for /f "usebackq delims=" %%S in ("%STALE%") do (
    set "REL=%%S"
    set "REL=!REL:%PREFIX%=!"
    set "STMP=%CST%\!REL!"
    for %%D in ("!STMP!") do if not exist "%%~dpD" mkdir "%%~dpD" >nul 2>&1
    break > "!STMP!"
)
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
REM :resolve_test <name> -- sets RESOLVED to the fully qualified class name.
REM A dotted name is taken as-is; a simple name is found under target\test-classes.
REM ---------------------------------------------------------------------------
:resolve_test
set "Q=%~1"
if not "!Q:.=!"=="!Q!" (
    set "RESOLVED=%Q%"
    exit /b 0
)
REM Quoting the item here would put literal quotes inside the file name.
for /r "%TEST_OUT%" %%F in (%Q%.class) do (
    if not defined RESOLVED (
        if exist "%%~fF" (
            call :class_name "%%~fF"
            set "RESOLVED=!CLASSNAME!"
        )
    )
)
exit /b 0

REM ---------------------------------------------------------------------------
REM :class_name <absolute .class path> -- sets CLASSNAME to a dotted class name.
REM ---------------------------------------------------------------------------
:class_name
set "CN=%~1"
set "CN=!CN:%ROOT%\target\test-classes\=!"
set "CN=!CN:.class=!"
set "CN=!CN:\=.!"
set "CLASSNAME=!CN!"
exit /b 0

REM ---------------------------------------------------------------------------
REM :run_junit -- runs the accumulated CHUNK of test classes and clears it.
REM ---------------------------------------------------------------------------
:run_junit
if not defined CHUNK exit /b 0
echo [devtest] running:!CHUNK!
set "ALSOFT_DRIVERS=null"
java -Djava.awt.headless=true -cp "%TEST_OUT%;%MAIN_OUT%;%RES_DIR%;%DEPCP%" org.junit.runner.JUnitCore!CHUNK!
if errorlevel 1 set "FAILED=1"
set "CHUNK="
exit /b 0

REM ---------------------------------------------------------------------------
REM :ms <time string> -- sets MSVAL to milliseconds since midnight.
REM %TIME% is HH:MM:SS.cc; leading zeros must be stripped because set /a reads
REM "08" as octal and fails.
REM ---------------------------------------------------------------------------
:ms
setlocal EnableDelayedExpansion
set "H=0"
set "M=0"
set "S=0"
set "CS=0"
for /f "tokens=1-4 delims=:., " %%a in ("%~1") do (
    set "H=%%a"
    set "M=%%b"
    set "S=%%c"
    set "CS=%%d"
)
for %%v in (H M S CS) do (
    if "!%%v:~0,1!"=="0" set "%%v=!%%v:~1!"
    if not defined %%v set "%%v=0"
)
set /a "MSVAL=(H*3600000)+(M*60000)+(S*1000)+(CS*10)"
endlocal & set "MSVAL=%MSVAL%"
exit /b 0

:usage
echo usage: tools\devtest.bat [--full] [--all ^| TestClass ...]
popd
exit /b 2

:fail
popd
exit /b 1
