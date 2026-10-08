# Aethon for Android

The Android client is a native Java application using Android `VpnService`. It runs official, unmodified Aether 2.3.0 as a supervised local core and routes the VPN file descriptor through HEV Socks5 Tunnel 2.16.0. Android and Windows have separate platform pins in `scripts/aether-pins.json`.

Version 2.2.0 provides the current Android VPN interface, localization, connection telemetry, split tunneling, update checks, resumable APK downloads, verification, notifications, and Android package installation handoff.

Core v2.3.0 replaces Stealth with Verified. Saved scan slot 3 selects Verified; legacy intent aliases normalize without resetting preferences. Ironclad remains separate. Exit-country policy stays disabled/unexposed for every protocol because of the known upstream WireGuard startup-order issue. Location remains descriptive, including Iran. Existing Tor-through-WARP retains the no-bridge policy.

Supported APK ABIs:

- ARMv7 (`armeabi-v7a`) for compatible 32-bit Android devices.
- ARM64 (`arm64-v8a`) for modern Android phones and tablets.
- x86_64 for Android emulators and compatible devices.
- Universal APK containing all three ABIs.

Build prerequisites are JDK 17 or newer, Android SDK Platform 35, and Android NDK 27.2.12479018. Native binaries are built from the pinned HEV source commit and are not committed; prepare and verify their provenance first. Development APKs must use the tracked builder from the repository root:

```powershell
python scripts/build-codex-android.py --help
```

The resumed Phase 2 builder reuses the existing hash-anchored native input chain in `work/phase1-20260927/PHASE_1_NATIVE_INPUTS.json`: Core, fresh selected HEV build A, Psiphon and Lyrebird for all three ABIs. It does not accept arbitrary native directories, download binaries, or fall back to old staging. Gradle stages and verifies all twelve payloads; `codexNativeDir` remains prohibited.

Check the existing reservation without building, creating an output directory, or consuming its number:

```powershell
python -B scripts/build-codex-android.py --build-id dev.013 --preflight
```

After implementation and validation gates pass, the same builder requires an explicit `--build-id`, workspace-local `--gradle-home` and `--android-user-home`, `--sdk`, and a confirmed `--expected-signer-sha256`. The existing debug key must match that certificate before outputs are reserved. There is no automatic Codex alias or numbering increment. Candidate outputs use `development-builds/<build-id>/`, Gradle outputs use `android/app/build-dev/<build-id>/`, and the active version name remains `2.2.0`. Existing output directories and consumed reservations are rejected. The builder snapshots source and native manifests, runs tests/lint/native verification, checks all embedded native payloads and signatures, and appends build evidence to `BUILD_HISTORY_V2.2.0.md`. Preflight is not APK verification or runtime approval.

Fresh installations and Reset to defaults use Turbo scan mode, Balanced obfuscation, and gool / WARP-in-WARP. Explicit saved scan choices retain their indices during upgrade and backup/restore. The application uses Android package names for Include/Exclude split tunneling. Android's system VPN permission is requested only when Device VPN starts; Quick Settings permission prompts open the existing Aethon activity.

Configurations groups existing controls into General / Connection, Protocol & Transport, Privacy & TLS, Routing & DNS, Proxy & Chaining, Zero Trust / Organization, and Advanced. Each section has English/Persian help and can expand independently; General starts open. Section and page state survive activity recreation. The header and status area intentionally remain white in both themes, with dark icons and text.

In codex.003, Proxy mode uses **HTTP `127.0.0.1:1818`** and **SOCKS5 `127.0.0.1:1819`**, both from one Aether connection. These loopback endpoints appear in Configurations → Proxy & Chaining. Clients using the opposite codex.002 assignment must update their ports. Device VPN retains its saved SOCKS/optional HTTP settings. Both Proxy protocols must pass real HTTPS traffic before Connected; unused readiness requests are cancelled and a fresh trace may be reused once for location within the same process and session.
