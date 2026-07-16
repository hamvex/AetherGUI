$ErrorActionPreference = "Stop"
$version = if ($env:AETHER_CORE_VERSION) { $env:AETHER_CORE_VERSION } else { "v1.1.1" }
$baseUrl = "https://github.com/CluvexStudio/Aether/releases/download/$version"
$archiveName = "aether-windows-x86_64.zip"
$checksumName = "$archiveName.sha256"
$temp = Join-Path ([System.IO.Path]::GetTempPath()) "aether-gui-$([guid]::NewGuid())"
$destination = Join-Path $PSScriptRoot "..\src-tauri\binaries\aether-x86_64-pc-windows-msvc.exe"
try {
  New-Item -ItemType Directory -Force $temp | Out-Null
  $archive = Join-Path $temp $archiveName
  $checksum = Join-Path $temp $checksumName
  Invoke-WebRequest -UseBasicParsing "$baseUrl/$archiveName" -OutFile $archive
  Invoke-WebRequest -UseBasicParsing "$baseUrl/$checksumName" -OutFile $checksum
  $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split "\s+")[0].ToLowerInvariant()
  if ($expected -notmatch "^[a-f0-9]{64}$") { throw "The upstream checksum file is invalid." }
  $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $expected) { throw "Aether core checksum mismatch. Expected $expected, got $actual." }
  $expanded = Join-Path $temp "expanded"
  Expand-Archive -LiteralPath $archive -DestinationPath $expanded
  $binary = Get-ChildItem -LiteralPath $expanded -Recurse -Filter "aether.exe" | Select-Object -First 1
  if (-not $binary) { throw "aether.exe was not found in the verified upstream archive." }
  New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
  Copy-Item -LiteralPath $binary.FullName -Destination $destination -Force
  Write-Host "Prepared verified Aether $version core at $destination"
}
finally {
  if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
