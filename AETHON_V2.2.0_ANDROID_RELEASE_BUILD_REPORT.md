# Aethon v2.2.0 Android Build Report

**Date:** 2026-10-08  
**Project:** `D:\project\AetherGUI-v2.2.0`  
**Git branch:** `main`  
**Baseline commit:** `32e8c5c`

## 1. Canonical project state

The v2.2.0 directory is now a standalone Git repository on local branch `main`.

- The old repository history was preserved by fetching the original `main` branch from the legacy repository.
- The v2.2.0 source was committed on top of that history as `32e8c5c`.
- No remote Git history was modified, force-pushed, or rewritten.
- No GitHub release, merge, or publication was performed.
- The old `D:\project\AetherGUI-v2.1.2` folder remains untouched.

## 2. Android signing identity

The original production release signing identity was not available.

Checked locations:

- `android/.android-signing/signing.properties`
- Android signing environment variables
- Project keystore/JKS/P12/PFX files
- Old v2.1.2 workspace signing material

No production release keystore or signing credentials were found. Only the existing Android debug keystore was available.

Because production release signing credentials were unavailable, no release APKs were generated and the debug key was not substituted for a release identity. Four clearly identified DEBUG APKs were produced as interim artifacts.

## 3. Build configuration

| Field | Value |
| --- | --- |
| Application version | `2.2.0` |
| Android versionName | `2.2.0` |
| Android versionCode | `29` |
| Package ID | `io.github.hamvex.aethergui` |
| Aether Core | official, unmodified `v2.3.0` |
| Supported ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, Universal |

The latest published version in the old repository was `2.1.1` with `versionCode 27`; `29` is higher.

No VPN connection logic, protocols, UI, or native libraries were changed from the validated dev.037 baseline.

## 4. Automated validation

| Check | Result |
| --- | --- |
| Clean Android build | Success |
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

## 5. Debug APK artifacts

The following DEBUG APKs are in `release/`:

| Architecture | Artifact | Size | SHA-256 |
| --- | --- | ---: | --- |
| arm64-v8a | `Aethon-VPN-v2.2.0-Android-arm64-v8a-DEBUG.apk` | 31,135,255 | `967a6b63d344d38382f5c70b499d49a8c7ff3085cf01e34498545dd1a3ea8e74` |
| armeabi-v7a | `Aethon-VPN-v2.2.0-Android-armeabi-v7a-DEBUG.apk` | 30,414,435 | `6d02208d1a79cca56db0c1775df6e0622a4fd83e343ce1138463efb705a9a2a3` |
| x86_64 | `Aethon-VPN-v2.2.0-Android-x86_64-DEBUG.apk` | 33,732,563 | `6ee5d2c4ad33f3b836c08275ded3ac96049854fc511c058fcf6bb20ff44b5758` |
| Universal | `Aethon-VPN-v2.2.0-Android-universal-DEBUG.apk` | 81,139,383 | `931452a66b55a7d6602c94f287afaa4541de95b5203df8ad2e2d8898bd8382f9` |

`release/SHA256SUMS.txt` includes these APKs and the existing Windows v2.2.0 artifacts.

## 6. Embedded native library verification

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

## 7. Signing result

All four DEBUG APKs verify with APK Signature Scheme v2.

The debug signer certificate SHA-256 is:

- `68a8995c8e1bcb6d4bd5cc73369e29edc3f6e8bd94e1a7c3142a96e73be49d3d`

This preserves the dev.037 debug signer continuity, but it is not a production release signing identity.

## 8. Limitations

- No production release APKs were built because the original release signing identity is unavailable.
- No new release key was generated.
- The debug key was not presented as a release key.
- No ADB or physical-device testing was performed.
- No live VPN connection, speed, stress, or protocol testing was repeated.
- The old project folder remains untouched.
- No GitHub remote was modified and no release was published.

## 9. Conclusion

The canonical v2.2.0 Android source is fully migrated and validated.

Because the original production signing identity is unavailable, the only correct current output is the set of four clearly identified DEBUG APKs in `release/`. Production release APKs must wait until the original release signing identity is supplied.
