Get-ChildItem -Recurse src\main\java -Filter *.java |
  Sort-Object Length -Descending |
  Select-Object -First 14 |
  ForEach-Object { "{0,9} {1}" -f $_.Length, $_.FullName.Replace((Get-Location).Path + '\', '') }
