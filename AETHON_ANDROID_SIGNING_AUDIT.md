# Aethon Android Signing Audit

**Date:** 2026-10-08  
**Project:** `D:\project\AetherGUI-v2.2.0`  
**Audit type:** Read-only  
**Latest public release inspected:** Aethon VPN v2.1.1

## 1. Executive summary

The latest publicly released Android APKs were signed with a dedicated production release certificate, not the current v2.2.0 debug certificate.

The original production signing identity is present on this machine in the legacy repository root:

- Keystore: `D:\project\AetherGUI\android\.android-signing\firstham-aethergui.jks`
- Properties: `D:\project\AetherGUI\android\.android-signing\signing.properties`

That keystore’s certificate exactly matches the certificate used by the public v2.1.1 APKs.

Therefore:

- The current v2.2.0 DEBUG APKs cannot update the public v2.1.1 release because their signing certificate differs.
- A properly signed v2.2.0 release APK built with the original release keystore can update the public v2.1.1 release without uninstalling it.

## 2. Latest public Android release

The latest official GitHub release is:

- Repository: `hamvex/AetherGUI`
- Release: `Aethon VPN v2.1.1`
- Tag: `v2.1.1`
- Published: 2026-09-13T20:46:21Z

The release contains four Android APKs. All four were inspected.

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `Aethon-VPN-v2.1.1-Android-arm64-v8a.apk` | 6,346,030 | `38672053a9d44e4b3361ffc319f0a24b06bac6ae8910ee44a734cafdc6f2336f` |
| `Aethon-VPN-v2.1.1-Android-armv7.apk` | 5,648,558 | `8d2d1c610a6c1a605f66db05f23d66092d41f88883366c233fb92b952768ef84` |
| `Aethon-VPN-v2.1.1-Android-Universal.apk` | 14,117,442 | `50bce794610daacb74b30e5fa092e5b0a4561600982e84aa550977addf5897c9` |
| `Aethon-VPN-v2.1.1-Android-x86_64.apk` | 6,648,532 | `786e0d78ebf927d8b468814681e5e4bfa703945d8aeca1293eb7e121a4d67ef3` |

Package metadata from the public APKs:

- Package: `io.github.hamvex.aethergui`
- versionName: `2.1.1`
- versionCode: `27`

## 3. Public release signing certificate

All four public v2.1.1 APKs verify with APK Signature Scheme v2 and use the same signer.

Certificate details:

- DN: `CN=Firstham AetherGui, OU=Android, O=hamvex, L=Dubai, ST=Dubai, C=AE`
- SHA-256 fingerprint: `1e5a37ef9bee8f3be747f18d75fd9cadc24c302ae327d53d480cac162ca7e100`
- Key algorithm: RSA
- Key size: 4096 bits

This is a dedicated release certificate, not an Android debug certificate.

## 4. Current v2.2.0 debug certificate

The current v2.2.0 debug APKs use:

- DN: `C=US, O=Android, CN=Android Debug`
- SHA-256 fingerprint: `68a8995c8e1bcb6d4bd5cc73369e29edc3f6e8bd94e1a7c3142a96e73be49d3d`
- Key algorithm: RSA
- Key size: 2048 bits

This certificate does not match the public release certificate.

## 5. Original release keystore

The original production signing identity was located in the legacy repository root:

- `D:\project\AetherGUI\android\.android-signing\firstham-aethergui.jks`
- `D:\project\AetherGUI\android\.android-signing\signing.properties`

The keystore certificate has:

- DN: `CN=Firstham AetherGui, OU=Android, O=hamvex, L=Dubai, ST=Dubai, C=AE`
- SHA-256 fingerprint: `1e5a37ef9bee8f3be747f18d75fd9cadc24c302ae327d53d480cac162ca7e100`
- Alias: `firstham-aethergui`

This fingerprint exactly matches the public v2.1.1 APK signing certificate.

No passwords, private keys, or secret values are reproduced in this report.

## 6. Historical signing configuration

The project’s signing configuration supports two mechanisms:

1. A local properties file:
   - `android/.android-signing/signing.properties`
   - Expected keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`

2. Environment variables:
   - `ANDROID_KEYSTORE_PATH`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`

The GitHub Actions release workflow also expects:

- `ANDROID_KEYSTORE_BASE64`

and reconstructs:

- `android/.android-signing/firstham-aethergui.jks`


The signing directory is ignored by Git, so the keystore and properties file were never committed to repository history.


## 7. Upgrade compatibility

Public v2.1.1:

- Package ID: `io.github.hamvex.aethergui`
- versionCode: `27`

Current v2.2.0:

- Package ID: `io.github.hamvex.aethergui`
- versionCode: `29`


However, Android also requires signing compatibility. The public release certificate and the current debug certificate differ.

Therefore:

- Current v2.2.0 DEBUG APKs cannot upgrade the public v2.1.1 installation.
- A v2.2.0 release APK signed with the original release keystore can upgrade the public v2.1.1 installation without uninstalling it.

## 8. Recommended next action

Use the original release keystore from:

- `D:\project\AetherGUI\android\.android-signing\firstham-aethergui.jks`


Do not generate a new key and do not substitute the debug key.

The signing material should be configured in the canonical v2.2.0 project or supplied through the existing environment-variable/CI mechanism before building release APKs.

## 9. Audit constraints honored

- No project source or version numbers were modified.
- No APKs were rebuilt.
- No keys were generated or replaced.
- No ADB or physical-device installation was used.
- Nothing was published or pushed to GitHub.
- No files were deleted.
- No private keys, keystore passwords, tokens, or secrets are included in this report.
