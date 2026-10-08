$ErrorActionPreference = "Stop"
$pins = (Get-Content -LiteralPath (Join-Path $PSScriptRoot "aether-pins.json") -Raw | ConvertFrom-Json).windows
$version = if ($env:AETHER_CORE_VERSION) { $env:AETHER_CORE_VERSION } else { $pins.version }
$baseUrl = if ($version -eq $pins.version) { $pins.downloadBaseUrl } else { "https://github.com/CluvexStudio/Aether/releases/download/$version" }
$archiveName = "aether-windows-x86_64.zip"
$temp = Join-Path ([System.IO.Path]::GetTempPath()) "aether-gui-$([guid]::NewGuid())"
$destination = Join-Path $PSScriptRoot "..\src-tauri\binaries\aether-x86_64-pc-windows-msvc.exe"

# The expected digest comes from this repository, not from the server being verified. The
# archive and its .sha256 companion share one base URL and therefore one trust boundary, so
# fetching the checksum from beside the archive would only prove the download was intact.
# See scripts/aether-pins.json for where these values come from.
if ($env:AETHER_EXPECTED_SHA256) {
  $expected = $env:AETHER_EXPECTED_SHA256.Trim().ToLowerInvariant()
  Write-Host "Using the caller-supplied Aether digest for $version."
}
elseif ($version -eq $pins.version) {
  $expected = $pins.archives.$archiveName
}
else {
  throw "Aether $version is not pinned. Either update scripts/aether-pins.json in a reviewed commit, or set AETHER_EXPECTED_SHA256 to the digest you have verified for $archiveName. This script will not accept the release's own checksum file as the authority."
}
if ($expected -notmatch "^[a-f0-9]{64}$") { throw "The pinned Aether digest for $archiveName is not a SHA-256 value." }

try {
  New-Item -ItemType Directory -Force $temp | Out-Null
  $archive = Join-Path $temp $archiveName
  $cacheArchive = if ($env:AETHER_ASSET_CACHE) { Join-Path $env:AETHER_ASSET_CACHE $archiveName } else { $null }
  if ($cacheArchive -and (Test-Path -LiteralPath $cacheArchive -PathType Leaf)) {
    Copy-Item -LiteralPath $cacheArchive -Destination $archive
  } else {
    Invoke-WebRequest -UseBasicParsing "$baseUrl/$archiveName" -OutFile $archive
  }
  $stream = [System.IO.File]::OpenRead($archive)
  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    $actual = ([System.BitConverter]::ToString($sha256.ComputeHash($stream))).Replace("-", "").ToLowerInvariant()
  }
  finally {
    $stream.Dispose()
    $sha256.Dispose()
  }
  if ($actual -ne $expected) { throw "Aether core checksum mismatch. Expected $expected, got $actual." }
  $expanded = Join-Path $temp "expanded"
  Expand-Archive -LiteralPath $archive -DestinationPath $expanded
  $binary = Get-ChildItem -LiteralPath $expanded -Recurse -Filter "aether.exe" | Select-Object -First 1
  if (-not $binary) { throw "aether.exe was not found in the verified upstream archive." }

  # The archive digest covers the container; this covers the file that actually ships. It is
  # the same value src-tauri/src/process.rs refuses to launch without, so a mismatch fails
  # here at build time instead of on a user's machine at connect time.
  $expectedBinary = $pins.binary.$archiveName
  if ($version -eq $pins.version -and $expectedBinary) {
    $actualBinary = (Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualBinary -ne $expectedBinary) {
      throw "The extracted aether.exe does not match the pin in src-tauri/src/process.rs. Expected $expectedBinary, got $actualBinary."
    }
  }

  New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
  Copy-Item -LiteralPath $binary.FullName -Destination $destination -Force

  # privacy helpers: the official Windows archive bundles pt\psiphon-tunnel-core.exe
  # and pt\lyrebird.exe beside aether.exe. Install them under src-tauri\binaries\pt\ (the
  # layout core_pt_dir() in src-tauri/src/process.rs expects) with digest verification from
  # the same pins entry: the archive hash already covers them, and the payload pins let the
  # build fail fast if the upstream layout ever changes.
  if ($version -eq $pins.version -and $pins.pt) {
    $ptDir = Join-Path $PSScriptRoot "..\src-tauri\binaries\pt"
    New-Item -ItemType Directory -Force $ptDir | Out-Null
    foreach ($name in @("psiphon-tunnel-core.exe", "lyrebird.exe")) {
      $helper = Join-Path $expanded "pt\$name"
      if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
        throw "pt\$name was not found in the verified upstream archive."
      }
      $expectedHelper = $pins.pt.$name
      if ($expectedHelper) {
        $actualHelper = (Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHelper -ne $expectedHelper) {
          throw "The extracted pt\$name does not match the pin ($expectedHelper vs $actualHelper)."
        }
      }
      Copy-Item -LiteralPath $helper -Destination (Join-Path $ptDir $name) -Force
    }
  }
  Write-Host "Prepared verified Aether $version core at $destination"
  Write-Host "  archive SHA256 $actual (pinned)"
}
finally {
  if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
