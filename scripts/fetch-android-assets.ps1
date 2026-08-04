$ErrorActionPreference = "Stop"
$aetherVersion = if ($env:AETHER_CORE_VERSION) { $env:AETHER_CORE_VERSION } else { "v1.5.0" }
$hevVersion = "2.16.0"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$destination = Join-Path $root "android/app/src/main/jniLibs"
$temp = Join-Path ([System.IO.Path]::GetTempPath()) "aether-android-$([guid]::NewGuid())"
$targets = @(
    @{ Abi = "arm64-v8a"; Archive = "aether-android-arm64.tar.gz"; Hev = "hev-socks5-tunnel-android-arm64-v8a"; HevSha256 = "abf16444129b49f5efea76444f7763c5e8f1dc8cf83bb2973b56d48d6265b313" },
    @{ Abi = "x86_64"; Archive = "aether-android-x86_64.tar.gz"; Hev = "hev-socks5-tunnel-android-x86_64"; HevSha256 = "2d6d27630acf2195ddbb825ad0e458ad474cbabe26a35cffc6902e51f4a36bae" }
)

function Get-Sha256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($stream))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}

function Get-VerifiedFile([string]$Url, [string]$Path, [string]$Expected) {
    Invoke-WebRequest -UseBasicParsing $Url -OutFile $Path
    $actual = Get-Sha256 $Path
    if ($actual -ne $Expected.ToLowerInvariant()) { throw "SHA-256 mismatch for $Url. Expected $Expected, got $actual." }
}

try {
    New-Item -ItemType Directory -Force $temp | Out-Null
    foreach ($target in $targets) {
        $abiDir = Join-Path $destination $target.Abi
        New-Item -ItemType Directory -Force $abiDir | Out-Null
        $archive = Join-Path $temp $target.Archive
        $checksum = "$archive.sha256"
        $base = "https://github.com/CluvexStudio/Aether/releases/download/$aetherVersion"
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive)" -OutFile $archive
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive).sha256" -OutFile $checksum
        $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split "\s+")[0]
        $actual = Get-Sha256 $archive
        if ($actual -ne $expected.ToLowerInvariant()) { throw "Aether Android checksum mismatch for $($target.Abi)." }
        $expanded = Join-Path $temp "expanded-$($target.Abi)"
        New-Item -ItemType Directory $expanded | Out-Null
        Copy-Item -LiteralPath $archive -Destination (Join-Path $expanded "core.tar.gz")
        Push-Location $expanded
        try { & tar -xzf "core.tar.gz"; if ($LASTEXITCODE -ne 0) { throw "Could not extract $($target.Archive)." } }
        finally { Pop-Location }
        $core = Get-ChildItem -LiteralPath $expanded -Recurse -File -Filter "aether" | Select-Object -First 1
        if (-not $core) { throw "Aether executable was not found in $($target.Archive)." }
        Copy-Item -LiteralPath $core.FullName -Destination (Join-Path $abiDir "libaether.so") -Force

        $hevUrl = "https://github.com/heiher/hev-socks5-tunnel/releases/download/$hevVersion/$($target.Hev)"
        Get-VerifiedFile $hevUrl (Join-Path $abiDir "libhev-socks5-tunnel.so") $target.HevSha256
        Write-Host "Prepared Android native assets for $($target.Abi)"
    }
}
finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
