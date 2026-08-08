# Aethon

<p align="center">
  <img src="src-tauri/icons/icon.png" width="160" alt="Aethon logo">
</p>

Aethon is an independent Windows and Android client for the official [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) networking core. Version 1.11.0 bundles the verified Aether 1.5.0 core and provides system-wide VPN routing or a local SOCKS5 proxy through focused desktop and mobile interfaces.

[Persian documentation](README.fa.md) | [Releases](https://github.com/hamvex/AetherGUI/releases)

## What's new in 1.11.0

- Added the Android “Aethon VPN” Quick Settings tile. It toggles the existing VPN service, reflects connected/disconnected/unavailable state, and routes VPN permission prompts through the main activity.
- Added complete Android Persian language resources with persisted language selection, RTL-aware layouts, localized statistics, and Android 13+ locale configuration.
- Improved Android diagnostics with bounded, batched live logs, smooth newest-entry following, and reduced storage churn.
- Removed obsolete Android header and diagnostics copy, fixed dashboard metric overlap, enlarged the connection indicator, and compacted the Diagnostics and connection cards.
- Fixed Android Split Tunneling checkbox selection so checkbox taps select immediately without interfering with list scrolling.
- Changed fresh-install defaults on both clients to Turbo scanning and gool / WARP-in-WARP while preserving user selections across launches.
- Updated the Windows dashboard with the same default protocol/scan profile, larger status indicator, safer metric wrapping, compact Diagnostics copy, and batched live log rendering.
- Added focused default/tile lifecycle tests and verified Android debug/release resources with lint.

- Added Android include-only and exclude-app split tunneling with a searchable installed-app picker.
- Added Material 3 System, Light, and Dark themes across the Android interface.
- Added Smart Connect benchmarking across MASQUE, WireGuard, and gool using connection success, handshake speed, latency, and stability scoring.
- Added signed 32-bit ARMv7 APK support alongside ARM64, x86_64, the universal APK, and the Play bundle.
- Increased Android `versionCode` to `20` while preserving the package ID and release certificate for installation over previous signed releases.
- Retains the disconnect, Windows VPN routing, Android security, Aethon branding, and logo improvements introduced in 1.9.0.

## Features

- System-wide Windows VPN mode using a checksum-verified sing-box 1.13.14 TUN engine.
- Full tunnel, local-network bypass, and executable-based split tunneling.
- DNS leak protection, explicit IPv6 tunneling or blocking, and an optional session kill switch.
- Manual SOCKS5 mode at `127.0.0.1:1819` for applications that support a proxy directly.
- MASQUE over HTTP/3 or HTTP/2, WireGuard, and gool / WARP-in-WARP.
- Turbo, Balanced, Thorough, Stealth, and Ironclad endpoint scanning.
- Protocol-specific obfuscation, custom endpoints, configuration files, and reconnect controls.
- Live logs with selectable Error, Warning, Info, Debug, and Trace verbosity.
- Cloudflare connection self-test, public IP reporting, and Windows network recovery.
- Single-instance protection, localized system tray actions, and clean sidecar shutdown.

## Download

Download the latest files from the [Aethon 1.11.0 GitHub Release](https://github.com/hamvex/AetherGUI/releases/tag/v1.11.0):

- [`Aethon_1.11.0_all-platforms.zip`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_all-platforms.zip): universal release archive containing Windows x64 and Android ARMv7, ARM64, x86_64, universal APK, and Play bundle artifacts.
- [`Aethon_1.11.0_x64-setup.exe`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_x64-setup.exe): recommended Windows x64 installer.
- [`Aethon_1.11.0_x64_en-US.msi`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_x64_en-US.msi): Windows x64 MSI package.
- [`Aethon_1.11.0_x64-portable.zip`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_x64-portable.zip): portable Windows x64 build and required sidecars.
- [`Aethon_1.11.0_android-universal.apk`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_android-universal.apk): Android APK containing ARMv7, ARM64, and x86_64 native libraries.
- [`Aethon_1.11.0_android-armv7.apk`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_android-armv7.apk): APK for compatible 32-bit ARM Android devices.
- [`Aethon_1.11.0_android-arm64.apk`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_android-arm64.apk): smaller APK for most modern physical Android devices.
- [`Aethon_1.11.0_android-x86_64.apk`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_android-x86_64.apk): APK for compatible emulators and x86_64 devices.
- [`Aethon_1.11.0_android-play.aab`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/Aethon_1.11.0_android-play.aab): signed bundle for Google Play Console.
- [`SHA256SUMS.txt`](https://github.com/hamvex/AetherGUI/releases/download/v1.11.0/SHA256SUMS.txt): SHA-256 hashes for release verification.

Windows 10/11 x64 is supported. Published binaries are currently unsigned and may trigger Microsoft Defender SmartScreen.

Android 8.0 or newer is supported. Android asks for system VPN permission when VPN Mode starts. Split tunneling uses Android application package names rather than Windows executable paths.

## Windows installation and usage

1. Download and run `Aethon_1.11.0_x64-setup.exe`. Existing installations can be upgraded by running the newer installer.
2. The fresh-install defaults are **VPN Mode**, **gool / WARP-in-WARP**, and **Turbo**. Change them whenever needed; selections persist.
3. Select **Connect** and approve the narrowly scoped Windows elevation request used to create the TUN adapter.
4. Wait for the Connected state. The status panel displays the endpoint, routing mode, elapsed time, public IP, and proxy address.
5. Open **Diagnostics** to run the self-test or inspect live Aether and routing logs.
6. Select **Disconnect** to stop Aether, close the virtual adapter, and restore Windows routing.

Use Manual SOCKS5 mode when only selected proxy-aware applications should connect through Aethon. Configure those applications with host `127.0.0.1` and port `1819`.

The portable ZIP must be fully extracted before use. Keep `Aethon.exe`, `aether.exe`, and `sing-box.exe` together in the same directory.

## Android installation and usage

1. Download the universal APK, the ARM64 APK for most modern phones and tablets, or ARMv7 for a compatible 32-bit device.
2. Install it over the previous signed version; uninstalling the old version is not required.
3. Open Aethon, keep **Device VPN**, **gool / WARP-in-WARP**, and **Turbo**, then select **Connect securely**.
4. Approve Android's standard VPN permission dialog. Notification permission is used only for connection status and the foreground VPN service.
5. Select **Disconnect** in the application or its persistent VPN notification to close the VPN safely.

The Android application ID remains `io.github.hamvex.aethergui` for update compatibility. Release APKs keep the established signing certificate; packages signed by a different key cannot replace an installed release.

## Architecture

The GUI does not duplicate Aether's scanning, tunnel, obfuscation, identity, or SOCKS5 implementation. It runs the verified official core as a hidden supervised sidecar and maps validated settings to documented Aether environment variables.

- `src/`: dependency-free HTML, CSS, JavaScript, localization, and frontend assets.
- `src-tauri/src/settings.rs`: settings validation, persistence, and Aether environment mapping.
- `src-tauri/src/process.rs`: core lifecycle supervision, status parsing, watchdog, and logs.
- `src-tauri/src/routing.rs`: elevated TUN routing, split tunneling, DNS handling, and recovery.
- `scripts/fetch-aether.ps1`: downloads and checksum-verifies Aether 1.5.0.
- `scripts/fetch-routing-engine.ps1`: downloads and verifies the pinned sing-box release.
- `.github/workflows/release.yml`: tests, Windows builds, installer packaging, and tagged releases.

### Android architecture

The Android client is a separate native Java application under `android/`. Android `VpnService` creates the system TUN interface, the official Aether Android executable provides the local SOCKS5 tunnel, and the pinned HEV Socks5 Tunnel JNI library connects the VPN file descriptor to Aether. The application remains unprivileged and uses the standard Android VPN permission flow.

The Android build pins HEV 2.16.0 and compiles its JNI libraries with Android NDK 27.2.12479018 for ARMv7, ARM64, and x86_64. On Windows, the fetch script materializes upstream symlink placeholders before `ndk-build`, ensuring local and CI builds produce real JNI libraries.

## Safety notes

The GUI remains unelevated during normal operation. Windows elevation is requested only for the routing helper that creates the virtual adapter and applies routes.

The kill switch is session-scoped. It keeps strict TUN routing active while Aether reconnects, but it is not a persistent boot-time firewall. After an interrupted session, use the Diagnostics **Repair network** command or run:

```powershell
aether-gui.exe --repair-network
```

TLS validation remains enabled. GUI input is not executed through a command shell. Settings contain paths and connection preferences, not Aether private keys or certificates. A non-loopback SOCKS5 listener requires explicit risk acknowledgement.

## Development

Requirements: Windows 10/11 x64, Node.js 20+, Rust stable with the MSVC target, Visual Studio C++ Build Tools, and WebView2.

```powershell
npm ci
npm run fetch:core
npm run fetch:routing
npm run fetch:android
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
npm run dev
```

Production build:

```powershell
npm run build
npm run build:android
```

The executable is generated at `src-tauri/target/release/aether-gui.exe`. Installer bundles are generated under `src-tauri/target/release/bundle/`.

## License and trademark

Aethon is licensed under GNU AGPL v3.0. Aether is developed by CluvexStudio and remains the networking engine. This repository is an independent graphical frontend and is not endorsed by CluvexStudio.

The Aether name and branding are governed by the upstream [Aether Trademark Policy](TRADEMARK.md). Derivative clients using the Aether name or branding may require prior written permission from the Aether maintainers.
