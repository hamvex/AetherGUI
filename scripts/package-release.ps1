param([string]$Version = "2.0.0", [string]$AndroidVersion = "2.0.0")

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
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
Copy-Item -LiteralPath (Join-Path $repo "src-tauri/binaries/sing-box-x86_64-pc-windows-msvc.exe") -Destination (Join-Path $portableDir "sing-box.exe")
foreach ($file in @("LICENSE", "NOTICE.md", "TRADEMARK.md", "third-party/sing-box-LICENSE.txt")) {
    Copy-Item -LiteralPath (Join-Path $repo $file) -Destination $portableDir
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
Get-ChildItem -LiteralPath $releaseDir -File | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    "$($hash.Hash.ToLower())  $($_.Name)" | Add-Content -LiteralPath $checksums
}

$bundle = Join-Path $releaseDir "Aethon-VPN-v${Version}-all-platforms.zip"
Compress-Archive -Path (Get-ChildItem -LiteralPath $releaseDir -File | Select-Object -ExpandProperty FullName) -DestinationPath $bundle -Force
$bundleHash = Get-FileHash -LiteralPath $bundle -Algorithm SHA256
"$($bundleHash.Hash.ToLower())  $([IO.Path]::GetFileName($bundle))" | Add-Content -LiteralPath $checksums

Get-ChildItem -LiteralPath $releaseDir -File | Select-Object Name, Length
