$ErrorActionPreference = "Stop"
$version = "1.13.14"
$archiveName = "sing-box-$version-windows-amd64.zip"
$expected = "f580782c6dd10f7691c66cea1d7c421813c5fbf7e305d1ee7ce0c3a40d196341"
$url = "https://github.com/SagerNet/sing-box/releases/download/v$version/$archiveName"
$temp = Join-Path ([System.IO.Path]::GetTempPath()) "firstham-routing-$([guid]::NewGuid())"
$destination = Join-Path $PSScriptRoot "..\src-tauri\binaries\sing-box-x86_64-pc-windows-msvc.exe"
try {
  New-Item -ItemType Directory -Force $temp | Out-Null
  $archive = Join-Path $temp $archiveName
  Invoke-WebRequest -UseBasicParsing $url -OutFile $archive
  $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $expected) { throw "sing-box checksum mismatch. Expected $expected, got $actual." }
  $expanded = Join-Path $temp "expanded"
  Expand-Archive -LiteralPath $archive -DestinationPath $expanded
  $binary = Get-ChildItem -LiteralPath $expanded -Recurse -Filter "sing-box.exe" | Select-Object -First 1
  if (-not $binary) { throw "sing-box.exe was not found in the verified archive." }
  New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
  Copy-Item -LiteralPath $binary.FullName -Destination $destination -Force
  Write-Host "Prepared verified sing-box v$version routing engine at $destination"
}
finally {
  if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
