Set-Location 'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine'
$p = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c mvnw.cmd -q exec:java > tools\c3_boot.txt 2>&1' `
    -WorkingDirectory 'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine' `
    -PassThru
Start-Sleep -Seconds 45
taskkill /T /F /PID $p.Id | Out-Null
Write-Output ('killed ' + $p.Id)
