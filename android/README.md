# Aethon for Android

The Android client is a native Java application using Android `VpnService`. It runs the official Aether 1.5.0 Android executable as a supervised local SOCKS5 core and routes the VPN file descriptor through HEV Socks5 Tunnel 2.16.0.

Version 1.10.0 adds application pickers for include/exclude split tunneling, Material 3 system/light/dark themes, Smart Connect protocol scoring, and 32-bit ARM support.

Supported APK ABIs:

- ARMv7 (`armeabi-v7a`) for compatible 32-bit Android devices.
- ARM64 (`arm64-v8a`) for modern Android phones and tablets.
- x86_64 for Android emulators and compatible devices.
- Universal APK containing all three ABIs.

Build prerequisites are JDK 17, Android SDK Platform 35, and Android NDK 27.2.12479018. Native binaries are built from the pinned HEV source commit and are not committed; prepare their verified copies first:

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\fetch-android-assets.ps1
.\gradlew.bat assembleDebug
```

The fetch script recursively clones and verifies HEV 2.16.0, expands Windows symlink placeholders, and runs `ndk-build` for `armeabi-v7a`, `arm64-v8a`, and `x86_64`. For a release build run:

```powershell
.\gradlew.bat assembleRelease lintRelease
```

The application uses Android package names for Include/Exclude split tunneling. Android's system VPN permission is requested only when VPN Mode starts.
