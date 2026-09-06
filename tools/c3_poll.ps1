Set-Location 'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine'
$deadline = (Get-Date).AddSeconds(540)
$st = ''
while ((Get-Date) -lt $deadline) {
    $st = git status --short -- src/main/resources/shaders/ src/main/java/com/voxel/
    if ($st) { break }
    Start-Sleep -Seconds 20
}
if ($st) {
    Write-Output 'CHANGES:'
    Write-Output $st
} else {
    Write-Output 'no new src changes after 540s'
}
