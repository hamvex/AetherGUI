# Firstham AetherGui

Firstham AetherGui is an independent, beginner-friendly Windows desktop frontend for [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether). It keeps the networking core separate and runs the official Aether executable as a hidden, supervised sidecar.

[فارسی](README.fa.md)

For installation, settings, troubleshooting, v2rayN, and Proxifier instructions, see the [complete Persian guide](docs/GUIDE.fa.md).

## Features

- Connect, disconnect, reconnect and clear state reporting
- MASQUE, WireGuard and gool / WARP-in-WARP
- Turbo, Balanced, Thorough and Stealth scanning
- IPv4, IPv6 and dual-stack modes
- Protocol-specific obfuscation profiles
- MASQUE HTTP/3 or HTTP/2 transport
- Validated advanced addresses, ports, timeouts and configuration paths
- Live logs, copy/clear controls and Cloudflare SOCKS5 connection test
- Local settings persistence, reset to defaults and non-loopback safety acknowledgement
- System tray actions and clean child-process shutdown
- Single-instance protection
- Modern white and blue Windows interface
- Persistent five-step first-launch welcome wizard
- Searchable built-in English documentation and setup guide
- In-app v2rayN and Proxifier instructions with traffic-flow diagrams
- Scoped About page with upstream attribution and Telegram link
- Compact connection-first layout with separate diagnostics
- Complete English and Persian localization with remembered language selection
- Full RTL mirroring and a bundled Vazirmatn Persian font
- Localized tray menu, onboarding, tooltips, statuses, and built-in documentation

## Download

Download the NSIS installer, MSI, or portable archive from [Releases](https://github.com/hamvex/AetherGUI/releases).

Windows x64 is currently supported. Published binaries are unsigned unless a release explicitly says otherwise.

## Independent architecture

This repository contains only the GUI. It does not fork or duplicate Aether's scanning, tunnelling, obfuscation, identity, or SOCKS5 implementation. The verified upstream `aether.exe` is bundled at build time and controlled without invoking a shell.

- `src/` — dependency-free HTML, CSS and JavaScript UI
- `src-tauri/src/settings.rs` — validation, persistence and exact environment mapping
- `src-tauri/src/process.rs` — hidden process lifecycle, async logs and state parsing
- `src-tauri/src/lib.rs` — Tauri commands, diagnostics, tray and single-instance handling
- `scripts/fetch-aether.ps1` — downloads and checksum-verifies the official Aether core
- `.github/workflows/release.yml` — tests, Windows packaging and tagged releases

## Development

Requirements: Windows 10/11 x64, Node.js 20+, Rust stable with the MSVC target, Visual Studio C++ Build Tools and WebView2.

```powershell
npm ci
npm run fetch:core
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
npm run dev
```

Production build:

```powershell
npm run build
```

Installers are generated in `src-tauri/target/release/bundle/`.

To use a locally built core instead of downloading one:

```powershell
$env:AETHER_CORE_BINARY = "C:\path\to\aether.exe"
npm run build
```

## Security

TLS validation remains enabled. User input is never executed through a shell. Settings do not contain Aether private keys or certificates. A non-loopback SOCKS5 listener requires explicit acknowledgement.

Please report vulnerabilities privately using GitHub Security Advisories.

## License and attribution

GNU Affero General Public License v3.0. Aether is developed by CluvexStudio and remains the networking engine; this repository is an independent graphical frontend.
