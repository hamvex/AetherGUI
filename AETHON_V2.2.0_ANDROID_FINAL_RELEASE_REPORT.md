# Aethon v2.2.0 Android Final Release Build Report

**Date:** 2026-10-08  
**Project:** `D:\project\AetherGUI-v2.2.0`  
**Git branch:** `main`  
**Baseline commit:** `ce131b5`

## 1. Signing identity

The canonical project now uses the original production release signing identity located in:

- `D:\project\AetherGUI\android\.android-signing\`

The signing material was copied into the canonical project’s ignored directory:

- `D:\project\AetherGUI-v2.2.0\android\.android-signing\`

No new key was generated.

The release certificate is:

- DN: `CN=Firstham AetherGui, OU=Android, O=hamvex, L=Dubai, ST=Dubai, C=AE`
- SHA-256 fingerprint: `1e5a37ef9bee8f3be747f18d75fd9cadc24c302ae327d53d480cac162ca7e100`
- Key algorithm: RSA
- Key size: 4096 bits

This certificate exactly matches the certificate used by the public Aethon VPN v2.1.1 Android APKs.

The signing directory is ignored by Git. No passwords, private keys, or secret values are included in this report.

## 2. Build configuration

| Field | Value |
| --- | --- |
| Application version | `2.2.0` |
| Android versionName | `2.2.0` |
| Android versionCode | `29` |
| Package ID | `io.github.hamvex.aethergui` |
| Aether Core | official, unmodified `v2.3.0` |
| Supported ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, Universal |

No VPN functionality, UI, protocols, or Core binaries were changed.

## 3. Automated validation

| Check | Result |
| --- | --- |
| Clean release build | Success |
| Unit tests | 303 passed / 0 failed / 0 errors / 0 skipped |
| Lint | 0 Fatal / 0 Error / 101 warnings |
| APK signatures | APK Signature Scheme v2 verified |
| Native library SHA-256 | All embedded payloads matched the pinned dev.037 manifest |
| ABI verification | Correct ABI set in every APK |
| 16-KiB alignment | Verified |
| Package identity | `io.github.hamvex.aethergui` |
| versionName | `2.2.0` |
| versionCode | `29` |

No ADB command was run and no APK was installed on a physical device.

## 4. Release APK artifacts

The following release APKs are in `release/`:

| Architecture | Artifact | Size | SHA-256 |
| --- | --- | ---: | --- |
| arm64-v8a | `Aethon-VPN-v2.2.0-Android-arm64-v8a.apk` | 24,689,845 | `25baeef458ab5a8c608ae2867aa1f62c81978b55a309e0139324e56b2d33cea8` |
| armeabi-v7a | `Aethon-VPN-v2.2.0-Android-armeabi-v7a.apk` | 24,019,673 | `1af9b7ed803fa7847f4bacf8e4e497f2de7c9fe9ba8ea5ba4558affe98e92e9d` |
| x86_64 | `Aethon-VPN-v2.2.0-Android-x86_64.apk` | 26,784,017 | `20a909d50565ed9addd4de408507456cf27931858dc6ba8d2c2a6a908c1ff58f` |
| Universal | `Aethon-VPN-v2.2.0-Android-universal.apk` | 70,548,401 | `3c502dac5be107a28513595f90c0567220d4a09794756fd780b00d7d4faf7bed` |

`release/SHA256SUMS.txt` includes these APKs, the existing DEBUG APKs, and the existing Windows v2.2.0 artifacts.

## 5. Embedded native library verification

All embedded native libraries matched the exact dev.037 pinned digests:

### arm64-v8a

- `libaether.so`: `2b44db73249eab204416c4e4977151db47aee487e1b96bae3c7e729f2812680e`
- `libhev-socks5-tunnel.so`: `6eb00a2ca1098c4253957b1d0de730e75c0bbf6db209415161b1052d451691a2`
- `liblyrebird.so`: `185cdbcfb8189cb502e841bc21ee5e19545bd0c4699bb656d21028ef5eacbd73`
- `libpsiphon-tunnel-core.so`: `7cab04ebf82ceed76ae53b99c306db26233b80825b930e2e7288f07a4087387a`

### armeabi-v7a

- `libaether.so`: `d8f3865a33366831aef881685f6c2d6e85c934c70fd18c38aa920ba140bb7bfb`
- `libhev-socks5-tunnel.so`: `ffbf062eef55f94d6466974da00c4e754737796095aada017ed90979d7111173`
- `liblyrebird.so`: `9745d7799856c6993aae01ebd657472dce9e1049962eb820b6f525873b9cf667`
- `libpsiphon-tunnel-core.so`: `719bf754d197ecb2b320e6251ace79109dc1972c6675206950bf8cb5a55f03ca`

### x86_64

- `libaether.so`: `46735a4808500a21b5f95af340c6977cb0c7dad264fa57427903a9aaed2161f3`
- `libhev-socks5-tunnel.so`: `f867cb5b011e07c2fc9c482ba6f625c8d25b4b14f19e0c28c37695924eea00d5`
- `liblyrebird.so`: `cad26f72e7e6411e514131da7b0d477cbb2e248bb0c989a6bce6e4d4ce9dbd13`
- `libpsiphon-tunnel-core.so`: `0ffe5cf61bb12bf14310c1dd24ba6af0b15475baf52291121af793508129484f`

## 6. Upgrade compatibility

Public v2.1.1:

- Package: `io.github.hamvex.aethergui`
- versionCode: `27`

v2.2.0:

- Package: `io.github.hamvex.aethergui`
- versionCode: `29`

The package ID is unchanged, the versionCode is higher, and the release certificate matches the public release certificate. Therefore these release APKs can upgrade the public v2.1.1 installation without uninstalling it.

## 7. Limitations

- No physical-device or ADB testing was performed.
- No live VPN connection, speed, stress, or protocol testing was repeated.
- No GitHub remote was modified and no release was published.
- The old project folder and original signing material were preserved.

## 8. Conclusion

The official Aethon v2.2.0 Android release APKs are built, signed with the original production certificate, and fully validated.

The final artifacts are in `release/`.
