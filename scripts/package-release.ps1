param([string]$Version = "2.2.0", [string]$AndroidVersion = "2.2.0")

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$status = (& git -C $repo status --porcelain)
if ($status -and $env:AETHON_ALLOW_DIRTY -ne "1") {
    throw "Release packaging requires a clean git worktree. Set AETHON_ALLOW_DIRTY=1 only for a local non-release build."
}
$commit = (& git -C $repo rev-parse HEAD).Trim()
if (-not $commit) { throw "Could not determine the release build commit." }
$releaseDir = Join-Path $repo "release"
$portableDir = Join-Path $repo "portable"

foreach ($path in @($releaseDir, $portableDir)) {
    $resolvedParent = [IO.Path]::GetFullPath((Split-Path -Parent $path))
    if ($resolvedParent -ne [IO.Path]::GetFullPath($repo)) {
        throw "Refusing to clean a release directory outside the repository: $path"
    }
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}

Copy-Item -LiteralPath (Join-Path $repo "src-tauri/target/release/bundle/nsis/Aethon_${Version}_x64-setup.exe") -Destination (Join-Path $releaseDir "Aethon-VPN-v${Version}-Windows-x64-Installer.exe")
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/target/release/bundle/msi/Aethon_${Version}_x64_en-US.msi") -Destination (Join-Path $releaseDir "Aethon-VPN-v${Version}-Windows-x64.msi")
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/target/release/aether-gui.exe") -Destination (Join-Path $portableDir "Aethon.exe")
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/binaries/aether-x86_64-pc-windows-msvc.exe") -Destination (Join-Path $portableDir "aether.exe")
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/binaries/xray-x86_64-pc-windows-msvc.exe") -Destination (Join-Path $portableDir "xray.exe")
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/binaries/wintun.dll") -Destination (Join-Path $portableDir "wintun.dll")
foreach ($file in @("LICENSE", "NOTICE.md", "TRADEMARK.md", "third-party/xray-LICENSE.txt", "third-party/wintun-LICENSE.txt")) {
    Copy-Item -LiteralPath (Join-Path $repo $file) -Destination $portableDir
}
if ($env:AETHON_SIGN_SCRIPT) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $env:AETHON_SIGN_SCRIPT `
        (Join-Path $releaseDir "Aethon-VPN-v${Version}-Windows-x64-Installer.exe") `
        (Join-Path $releaseDir "Aethon-VPN-v${Version}-Windows-x64.msi") `
        (Join-Path $portableDir "Aethon.exe")
    if ($LASTEXITCODE -ne 0) { throw "Windows signing failed; release hashes were not generated." }
}
Compress-Archive -Path (Join-Path $portableDir "*") -DestinationPath (Join-Path $releaseDir "Aethon-VPN-v${Version}-Windows-x64-portable.zip") -Force

$androidOutputs = @{
    "android/app/build/outputs/apk/release/app-universal-release.apk" = "Aethon-VPN-v${AndroidVersion}-Android-Universal.apk"
    "android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk" = "Aethon-VPN-v${AndroidVersion}-Android-ARMv7.apk"
    "android/app/build/outputs/apk/release/app-arm64-v8a-release.apk" = "Aethon-VPN-v${AndroidVersion}-Android-ARM64.apk"
    "android/app/build/outputs/apk/release/app-x86_64-release.apk" = "Aethon-VPN-v${AndroidVersion}-Android-x86_64.apk"
    "android/app/build/outputs/bundle/release/app-release.aab" = "Aethon-VPN-v${AndroidVersion}-Android-AAB.aab"
}
foreach ($entry in $androidOutputs.GetEnumerator()) {
    Copy-Item -LiteralPath (Join-Path $repo $entry.Key) -Destination (Join-Path $releaseDir $entry.Value)
}

$checksums = Join-Path $releaseDir "SHA256SUMS.txt"
# The list is materialised before the first write. Streaming Get-ChildItem straight into a
# loop that appends SHA256SUMS.txt to the directory being enumerated can yield the checksum
# file itself and hash it while it is still half-written.
$artifacts = @(Get-ChildItem -LiteralPath $releaseDir -File | Sort-Object -Property Name)
$lines = foreach ($artifact in $artifacts) {
    "$((Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLower())  $($artifact.Name)"
}
# LF, not CRLF. Add-Content and Set-Content write CRLF on Windows, and `sha256sum -c` then
# folds the carriage return into the filename and reports every entry as missing - so the
# file published for users to verify against would not itself verify.
[IO.File]::WriteAllText($checksums, ($lines -join "`n") + "`n", (New-Object Text.ASCIIEncoding))

$bundle = Join-Path $releaseDir "Aethon-VPN-v${Version}-all-platforms.zip"
Compress-Archive -Path ($artifacts.FullName + @($checksums)) -DestinationPath $bundle -Force
$bundleHash = Get-FileHash -LiteralPath $bundle -Algorithm SHA256
[IO.File]::AppendAllText($checksums, "$($bundleHash.Hash.ToLower())  $([IO.Path]::GetFileName($bundle))`n", (New-Object Text.ASCIIEncoding))

$manifestEntries = @(Get-ChildItem -LiteralPath $releaseDir -File | Where-Object { $_.Name -ne "AETHON_RELEASE_MANIFEST.json" } | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{ artifact = $_.Name; size = $_.Length; sha256 = $hash }
})
[ordered]@{
    version = $Version
    build_commit = $commit
    build_date = [DateTime]::UtcNow.ToString("o")
    artifacts = $manifestEntries
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $releaseDir "AETHON_RELEASE_MANIFEST.json") -Encoding utf8

Get-ChildItem -LiteralPath $releaseDir -File | Select-Object Name, Length
