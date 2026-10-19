<#
Compiles only the Java sources whose .class is missing or stale.

Used by build-fast.bat. Kept in PowerShell because batch's %%~tS timestamp
handling is locale- and filesystem-dependent, which made the stale-file list
unreliable.

Exit code 0 on success (including "nothing to do"), 1 on compile failure.
#>
param(
  [string]$Javac = 'javac'
)

$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$srcRoot = Join-Path $root 'src\main\java'
$outRoot = Join-Path $root 'target\classes'

if (-not (Test-Path $srcRoot)) { Write-Host "[fast] no src\main\java"; exit 0 }
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

# Dependency classpath: target\classes first, then every jar maven resolved.
$deps = @()
$cpFile = Join-Path $root 'target\cp.txt'
if (Test-Path $cpFile) { $deps = (Get-Content $cpFile -Raw).Trim() }
$cp = (@($outRoot) + $deps) -join ';'

$stale = New-Object System.Collections.Generic.List[string]
Get-ChildItem $srcRoot -Recurse -Filter *.java | ForEach-Object {
    $rel = $_.FullName.Substring($srcRoot.Length + 1)
    # Package directory = the .java's own folder, minus the final filename.
    $clsDir = Join-Path $outRoot (Split-Path $rel -Parent)
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($rel)
    $own  = Join-Path $outRoot ($rel -replace '\.java$', '.class')

    # Some sources are entirely commented out (e.g. render/Renderer.java, kept
    # for reference). javac legitimately emits nothing for those, so they must be
    # skipped or they rebuild forever.
    $body = (Get-Content $_.FullName | Where-Object {
        $t = $_.Trim()
        $t -ne '' -and -not $t.StartsWith('//') -and -not $t.StartsWith('/*') -and -not $t.StartsWith('*')
    } | Select-Object -First 1)
    if ($null -eq $body) { return }

    # A .java may declare several package-private top-level types, whose .class
    # files are named <Type>.class and do NOT match the .java name (e.g.
    # BetaGenLayers.java emits BetaLayerIsland.class). Treat "any .class sharing
    # this file's leading stem" as its output so those converge.
    $emitted = $false
    if (Test-Path $own) { $emitted = $true }
    elseif (Test-Path $clsDir) {
        $prefix = $stem.Substring(0, [Math]::Min(8, $stem.Length))
        $emitted = @(Get-ChildItem $clsDir -Filter "$prefix*.class" -ErrorAction SilentlyContinue).Count -gt 0
    }

    if (-not $emitted) {
        $stale.Add($_.FullName)
    } else {
        $ref = if (Test-Path $own) { $own } else {
            @(Get-ChildItem $clsDir -Filter "$($stem.Substring(0,[Math]::Min(8,$stem.Length)))*.class" -EA SilentlyContinue |
              Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName
        }
        if ($ref -and (Test-Path $ref) -and $_.LastWriteTimeUtc -gt (Get-Item $ref).LastWriteTimeUtc) {
            $stale.Add($_.FullName)
        }
    }
}

if ($stale.Count -eq 0) {
    Write-Host '[fast] Up to date - nothing to compile.'
    exit 0
}

Write-Host "[fast] Compiling $($stale.Count) changed source file(s)..."
# -source/-target (not --release): JDK 8's javac rejects --release outright,
# and the game targets a Java 8 runtime. When built ON JDK 8 there is no
# newer java.base to accidentally link against, so source/target is safe here.
& $Javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $cp -d $outRoot @stale
if ($LASTEXITCODE -ne 0) {
    Write-Host '[fast] FAILED.'
    exit 1
}
Write-Host '[fast] OK.'
exit 0