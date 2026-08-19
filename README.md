# Aethon

Aethon is an independent Windows and Android client for the official [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) networking core. Windows version 2.0.0 bundles the verified Aether 1.7.0 core and provides system-wide VPN routing or a local SOCKS5 proxy through focused desktop and mobile interfaces.

[Releases](https://github.com/hamvex/AetherGUI/releases) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md)

## Windows 2.0.0 release notes

### Windows routing and settings

- Added transactional, single-session routing-helper and sing-box lifecycle management.
- Added recovery for stale Aethon-owned TUN adapters and failed routing sessions.
- Preserved sanitized sing-box exit diagnostics and immediate reconnect cleanup.
- Restored Scan Mode and protocol-specific MASQUE HTTP/3 or HTTP/2 transport controls.
- Migrated obsolete MASQUE obfuscation values without confusing them with Scan Mode.
- Kept the compact Connect, Configurations, and Settings navigation.

### Android-parity interface

- Added English and Persian application translations with right-to-left layout support.
- Removed Android locale overrides, Persian resources, RTL support, and the language selector.
- Added the Windows language selector and preserved VPN features and routing behavior.

### Application updates

- Added automatic update checks at startup and every 12 hours while Aethon is running.
- Added manual **Check for Updates** controls to Android and Windows settings.
- Added current version, latest version, update status, and GitHub release notes.
- Added an **Automatically download updates** preference.
- Android automatic downloads use Wi-Fi and Android DownloadManager for resumable transfers.
- Windows downloads the official x64 setup installer and reports progress inside the application.
- Added duplicate Android notification prevention and Android download progress notifications.
- Added SHA-256 verification on both platforms.
- Android additionally verifies that the downloaded APK uses the same signing certificate as the installed application.
- Installation uses Android FileProvider/package installer APIs and the verified Windows setup executable.
- Update URLs are restricted to the official `hamvex/AetherGUI` GitHub repository.

### Versions and compatibility

- Windows version: `2.0.0`
- Android version name: `2.0.0`
- Android version code: `23`
- Aether core: `1.7.0`
- sing-box routing engine: `1.13.14`
- Windows: Windows 10/11 x64
- Android: Android 8.0 or newer; ARMv7, ARM64, and x86_64

Existing VPN services, state management, routing recovery, Smart Connect, and split tunneling remain in place.

## Downloads

Download the release files from [Aethon 2.0.0](https://github.com/hamvex/AetherGUI/releases/tag/v2.0.0):

- [`Aethon-VPN-v2.0.0-all-platforms.zip`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-all-platforms.zip) — Windows and Android 2.0.0 release archive.
- [`Aethon-VPN-v2.0.0-Windows-x64-Installer.exe`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Windows-x64-Installer.exe) — recommended Windows installer.
- [`Aethon-VPN-v2.0.0-Windows-x64.msi`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Windows-x64.msi) — Windows MSI.
- [`Aethon-VPN-v2.0.0-Windows-x64-portable.zip`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Windows-x64-portable.zip) — portable Windows package.
- [`Aethon-VPN-v2.0.0-Android-Universal.apk`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Android-Universal.apk) — Android universal APK containing ARMv7, ARM64, and x86_64 libraries.
- [`Aethon-VPN-v2.0.0-Android-ARMv7.apk`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Android-ARMv7.apk) — 32-bit ARM APK.
- [`Aethon-VPN-v2.0.0-Android-ARM64.apk`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Android-ARM64.apk) — 64-bit ARM APK.
- [`Aethon-VPN-v2.0.0-Android-x86_64.apk`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Android-x86_64.apk) — x86_64 APK.
- [`Aethon-VPN-v2.0.0-Android-AAB.aab`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/Aethon-VPN-v2.0.0-Android-AAB.aab) — Play App Bundle.
- [`SHA256SUMS.txt`](https://github.com/hamvex/AetherGUI/releases/download/v2.0.0/SHA256SUMS.txt) — release checksums.

Windows binaries are currently unsigned and may trigger a SmartScreen warning. Android release packages are signed with the established Aethon Android signing certificate.

## Update source configuration

Both clients use the latest GitHub Release endpoint:

```text
https://api.github.com/repos/hamvex/AetherGUI/releases/latest
```

Future releases must include:

- `Aethon-VPN-v<version>-Windows-x64-Installer.exe`
- `Aethon-VPN-v<version>-Android-Universal.apk`
- SHA-256 asset digests supplied by GitHub or a `SHA256SUMS.txt` asset
- Release notes in the GitHub release body
- Android APKs signed with the same established signing key

No custom update backend is required. If the Android APK is distributed through Google Play, use of `REQUEST_INSTALL_PACKAGES` and direct self-updates should be reviewed against current Play policy.

## Windows usage

1. Install the x64 setup package or extract the portable archive.
2. Launch Aethon.
3. Keep **VPN Mode** selected for system-wide routing, or choose **Manual SOCKS5** for proxy-only use.
4. Select a protocol and scan mode, then press **Connect**.
5. Use Diagnostics for live logs, connection testing, and network recovery.

The local SOCKS5 listener defaults to `127.0.0.1:1819`. VPN mode may request administrator permission when configuring the TUN adapter and protected routes.

## Android usage

1. Install the universal APK or the APK matching the device architecture.
2. Approve Android VPN permission on first connection.
3. Select the desired mode and protocol.
4. Press **Connect**.
5. Optionally add the **Aethon VPN** Quick Settings tile.

The Android application ID remains `io.github.hamvex.aethergui` for update compatibility.

## Building

### Prerequisites

- Windows 10/11 x64
- Node.js 22
- Rust stable with the `x86_64-pc-windows-msvc` target
- Visual Studio Build Tools with MSVC
- Java 17
- Android SDK and NDK `27.2.12479018`
- WiX Toolset prerequisites used by Tauri

### Windows

```powershell
npm ci
npm run fetch:core
npm run fetch:routing
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
npm run build
```

Windows output is written under `src-tauri/target/release`.

### Android

Release signing credentials are required for distributable Android builds:

```powershell
$env:ANDROID_KEYSTORE_PATH = ".android-signing/firstham-aethergui.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "<password>"
$env:ANDROID_KEY_ALIAS = "<alias>"
$env:ANDROID_KEY_PASSWORD = "<password>"
npm run fetch:android
Set-Location android
./gradlew.bat assembleRelease bundleRelease lintRelease
```

Android output is written under `android/app/build/outputs`.

### Universal release archive

After both platform builds complete:

```powershell
npm run package:release
```

This creates Windows x64 installers, portable files, architecture-specific Android packages, checksums, and `Aethon-VPN-v2.0.0-all-platforms.zip` under `release`.

## Verification

```powershell
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
Set-Location android
./gradlew.bat testDebugUnitTest lintDebug lintRelease
```

## Project layout

- `src/` — Windows frontend.
- `src-tauri/` — Windows native process, routing, settings, and updater code.
- `android/` — native Android client.
- `scripts/` — verified dependency fetching and release packaging.
- `.github/workflows/release.yml` — Windows/Android CI and tagged release publishing.

## Attribution

Aethon is an independent frontend and is not the upstream Aether project. Aether remains the networking engine and is distributed under GPL-3.0. See [NOTICE.md](NOTICE.md), [TRADEMARK.md](TRADEMARK.md), and [LICENSE](LICENSE).
