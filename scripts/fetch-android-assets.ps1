$ErrorActionPreference = "Stop"

$aetherVersion = if ($env:AETHER_CORE_VERSION) { $env:AETHER_CORE_VERSION } else { "v1.5.0" }
$hevVersion = "2.16.0"
$hevCommit = "0a05221275a51a884d93328c55fc2fbc9e9b6974"
$ndkVersion = "27.2.12479018"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$destination = Join-Path $root "android/app/src/main/jniLibs"
$nativeBase = if ($env:PUBLIC) { Join-Path $env:PUBLIC "FirsthamAetherGuiNative" } else { Join-Path ([System.IO.Path]::GetTempPath()) "FirsthamAetherGuiNative" }
$temp = Join-Path $nativeBase ([guid]::NewGuid().ToString("N"))
$targets = @(
    @{ Abi = "arm64-v8a"; Archive = "aether-android-arm64.tar.gz" },
    @{ Abi = "x86_64"; Archive = "aether-android-x86_64.tar.gz" }
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

function Resolve-NdkRoot {
    $candidates = @(
        $env:ANDROID_NDK_HOME,
        $env:ANDROID_NDK_ROOT,
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "ndk/$ndkVersion" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "ndk/$ndkVersion" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk/ndk/$ndkVersion" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }
    if (-not $candidates) {
        throw "Android NDK $ndkVersion is required. Install it with sdkmanager 'ndk;$ndkVersion'."
    }
    return (Resolve-Path -LiteralPath $candidates[0]).Path
}

function Expand-WindowsSymlinkPlaceholders([string]$SourceRoot) {
    if (-not $IsWindows -and $PSVersionTable.PSEdition -ne "Desktop") { return }
    $links = @()
    Get-ChildItem -LiteralPath $SourceRoot -Recurse -File | Where-Object Length -lt 260 | ForEach-Object {
        $raw = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -eq $raw) { return }
        $targetText = $raw.Trim()
        if ($targetText -notmatch '^\.\.?[/\\][^\r\n]+$') { return }
        $target = Join-Path $_.DirectoryName ($targetText -replace '/', '\')
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $links += [pscustomobject]@{ Link = $_.FullName; Target = (Resolve-Path -LiteralPath $target).Path }
        }
    }
    foreach ($link in $links) {
        Copy-Item -LiteralPath $link.Target -Destination $link.Link -Force
    }
}

try {
    New-Item -ItemType Directory -Force $temp | Out-Null
    New-Item -ItemType Directory -Force $destination | Out-Null

    foreach ($target in $targets) {
        $abiDir = Join-Path $destination $target.Abi
        New-Item -ItemType Directory -Force $abiDir | Out-Null
        $archive = Join-Path $temp $target.Archive
        $checksum = "$archive.sha256"
        $base = "https://github.com/CluvexStudio/Aether/releases/download/$aetherVersion"
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive)" -OutFile $archive
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive).sha256" -OutFile $checksum
        $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split "\s+")[0]
        if ($expected -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid Aether checksum for $($target.Abi)." }
        $actual = Get-Sha256 $archive
        if ($actual -ne $expected.ToLowerInvariant()) { throw "Aether Android checksum mismatch for $($target.Abi)." }

        $expanded = Join-Path $temp "aether-$($target.Abi)"
        New-Item -ItemType Directory $expanded | Out-Null
        Copy-Item -LiteralPath $archive -Destination (Join-Path $expanded "core.tar.gz")
        Push-Location $expanded
        try {
            & tar -xzf "core.tar.gz"
            if ($LASTEXITCODE -ne 0) { throw "Could not extract $($target.Archive)." }
        }
        finally { Pop-Location }
        $core = Get-ChildItem -LiteralPath $expanded -Recurse -File -Filter "aether" | Select-Object -First 1
        if (-not $core) { throw "Aether executable was not found in $($target.Archive)." }
        Copy-Item -LiteralPath $core.FullName -Destination (Join-Path $abiDir "libaether.so") -Force
        Write-Host "Prepared verified Aether core for $($target.Abi)"
    }

    $hevSource = Join-Path $temp "hev-socks5-tunnel"
    & git clone --quiet --branch $hevVersion --depth 1 --recurse-submodules https://github.com/heiher/hev-socks5-tunnel.git $hevSource
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch HEV Socks5 Tunnel $hevVersion." }
    $checkedOutCommit = (& git -C $hevSource rev-parse HEAD).Trim()
    if ($checkedOutCommit -ne $hevCommit) { throw "Unexpected HEV commit $checkedOutCommit; expected $hevCommit." }
    $submoduleState = & git -C $hevSource submodule status --recursive
    if ($LASTEXITCODE -ne 0 -or ($submoduleState | Where-Object { $_ -match '^[+-]' })) {
        throw "HEV submodules do not match the pinned release."
    }
    Expand-WindowsSymlinkPlaceholders $hevSource

    $ndkRoot = Resolve-NdkRoot
    $ndkBuild = Join-Path $ndkRoot $(if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") { "ndk-build.cmd" } else { "ndk-build" })
    $libsOut = Join-Path $temp "hev-libs"
    $objOut = Join-Path $temp "hev-obj"
    & $ndkBuild "NDK_PROJECT_PATH=$hevSource" "APP_BUILD_SCRIPT=$(Join-Path $hevSource 'Android.mk')" "NDK_APPLICATION_MK=$(Join-Path $hevSource 'Application.mk')" 'APP_ABI=arm64-v8a x86_64' 'APP_CFLAGS=-O3 -DPKGNAME=hev/htproxy' "NDK_LIBS_OUT=$libsOut" "NDK_OUT=$objOut" -j 4
    if ($LASTEXITCODE -ne 0) { throw "HEV JNI build failed." }

    foreach ($target in $targets) {
        $library = Join-Path $libsOut "$($target.Abi)/libhev-socks5-tunnel.so"
        if (-not (Test-Path -LiteralPath $library -PathType Leaf)) { throw "HEV JNI library is missing for $($target.Abi)." }
        Copy-Item -LiteralPath $library -Destination (Join-Path $destination "$($target.Abi)/libhev-socks5-tunnel.so") -Force
        Write-Host "Built HEV JNI bridge for $($target.Abi)"
    }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolvedTemp = [System.IO.Path]::GetFullPath($temp)
        $resolvedBase = [System.IO.Path]::GetFullPath($nativeBase)
        if (-not $resolvedTemp.StartsWith($resolvedBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean an unexpected native build path: $resolvedTemp"
        }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
