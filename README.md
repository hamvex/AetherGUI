# Aethon

<p align="center">
  <img src="src-tauri/icons/icon.png" width="160" alt="Aethon logo">
</p>

Aethon is an independent Windows and Android client for the official [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) networking core. Version 1.9.0 bundles the verified Aether 1.5.0 core and provides system-wide VPN routing or a local SOCKS5 proxy through focused desktop and mobile interfaces.

[Persian documentation](README.fa.md) | [Releases](https://github.com/hamvex/AetherGUI/releases)

## What's new in 1.9.0

- Renamed the application to **Aethon** and introduced a new modern logo across Windows, Android, and installer assets.
- Fixed the Android Disconnect deadlock and prevented stopped or superseded sessions from reconnecting themselves.
- Fixed Windows Disconnect so Aether and system routing cleanup are both attempted even if one component reports an error.
- Fixed Windows VPN startup state updates and excluded Aethon/Aether processes from the TUN route to prevent proxy loops.
- Added failure rollback when Windows VPN routing cannot start, avoiding orphaned Aether processes.
- Hardened Android with target SDK 35, disabled cleartext traffic and backups, R8 shrinking, private components, and release-only signing enforcement.
- Increased Android `versionCode` to `19` while preserving the package ID and release certificate for installation over older signed releases.
- Added signed universal, ARM64, and x86_64 APKs plus a signed Android App Bundle for Play Console.

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

Download the latest files from [GitHub Releases](https://github.com/hamvex/AetherGUI/releases):

- `Aethon_1.9.0_x64-setup.exe`: recommended Windows installer.
- `Aethon_1.9.0_x64_en-US.msi`: MSI deployment package.
- `Aethon_1.9.0_x64-portable.zip`: portable Windows executable and required sidecars.
- `Aethon_1.9.0_android-universal.apk`: Android APK for ARM64 and x86_64 devices.
- `Aethon_1.9.0_android-arm64.apk`: smaller APK for most physical Android devices.
- `Aethon_1.9.0_android-x86_64.apk`: APK for compatible emulators and x86_64 devices.
- `Aethon_1.9.0_android-play.aab`: signed bundle for Google Play Console.
- `SHA256SUMS.txt`: SHA-256 hashes for release verification.

Windows 10/11 x64 is supported. Published binaries are currently unsigned and may trigger Microsoft Defender SmartScreen.

Android 8.0 or newer is supported. Android asks for system VPN permission when VPN Mode starts. Split tunneling uses Android application package names rather than Windows executable paths.

## Windows installation and usage

1. Download and run `Aethon_1.9.0_x64-setup.exe`. Existing installations can be upgraded by running the newer installer.
2. Keep **VPN Mode**, **MASQUE**, **Balanced**, and **HTTP/3** selected for the recommended configuration.
3. Select **Connect** and approve the narrowly scoped Windows elevation request used to create the TUN adapter.
4. Wait for the Connected state. The status panel displays the endpoint, routing mode, elapsed time, public IP, and proxy address.
5. Open **Diagnostics** to run the self-test or inspect live Aether and routing logs.
6. Select **Disconnect** to stop Aether, close the virtual adapter, and restore Windows routing.

Use Manual SOCKS5 mode when only selected proxy-aware applications should connect through Aethon. Configure those applications with host `127.0.0.1` and port `1819`.

The portable ZIP must be fully extracted before use. Keep `Aethon.exe`, `aether.exe`, and `sing-box.exe` together in the same directory.

## Android installation and usage

1. Download the universal APK, or the ARM64 APK for most modern phones and tablets.
2. Install it over the previous signed version; uninstalling the old version is not required.
3. Open Aethon, keep **Device VPN**, **MASQUE**, **Balanced**, and **HTTP/3**, then select **Connect securely**.
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

The Android build pins HEV 2.16.0 and compiles its JNI libraries with Android NDK 27.2.12479018 for ARM64 and x86_64. On Windows, the fetch script materializes upstream symlink placeholders before `ndk-build`, ensuring local and CI builds produce real JNI libraries.

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
