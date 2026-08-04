# Firstham AetherGui for Android

The Android client is a native Java application using Android `VpnService`. It runs the official Aether 1.5.0 Android executable as a supervised local SOCKS5 core and routes the VPN file descriptor through HEV Socks5 Tunnel 2.16.0.

Version 1.8.0 adds a polished white-and-blue Material 3 interface, connection and traffic status, diagnostics with live logs, advanced routing controls, quick reconnect, fail-closed kill switch behavior, split-app validation, and a Firstham Telegram channel card.

Supported APK ABIs:

- ARM64 (`arm64-v8a`) for modern Android phones and tablets.
- x86_64 for Android emulators and compatible devices.
- Universal APK containing both ABIs.

Build prerequisites are JDK 17, Android SDK Platform 35, and Android NDK 27.2.12479018. Native binaries are built from the pinned HEV source commit and are not committed; prepare their verified copies first:

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\fetch-android-assets.ps1
.\gradlew.bat assembleDebug
```

The fetch script recursively clones and verifies HEV 2.16.0, expands Windows symlink placeholders, and runs `ndk-build` for `arm64-v8a` and `x86_64`. For a release build run:

```powershell
.\gradlew.bat assembleRelease lintRelease
```

The application uses Android package names for Include/Exclude split tunneling. Android's system VPN permission is requested only when VPN Mode starts.
