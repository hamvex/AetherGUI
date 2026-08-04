# Firstham AetherGui for Android

The Android client is a native Java application using Android `VpnService`. It runs the official Aether 1.5.0 Android executable as a supervised local SOCKS5 core and routes the VPN file descriptor through HEV Socks5 Tunnel 2.16.0.

Supported APK ABIs:

- ARM64 (`arm64-v8a`) for modern Android phones and tablets.
- x86_64 for Android emulators and compatible devices.
- Universal APK containing both ABIs.

Build prerequisites are JDK 17 and Android SDK Platform 35. Native binaries are not committed; prepare their verified copies first:

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\fetch-android-assets.ps1
.\gradlew.bat assembleDebug
```

The application uses Android package names for Include/Exclude split tunneling. Android's system VPN permission is requested only when VPN Mode starts.
