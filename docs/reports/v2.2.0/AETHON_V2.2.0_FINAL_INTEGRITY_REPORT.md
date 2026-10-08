# Aethon v2.2.0 Final Pre-Publication Integrity Report

**Date:** 2026-10-09
**Project:** `D:\project\AetherGUI-v2.2.0`
**Scope:** Focused audit — artifact hashes, APK hash-to-filename discrepancy, APK identity/signature verification
**Overall verdict:** **PASS — publication is not blocked.**

## 1. Independent SHA-256 verification of release artifacts

All seven distribution artifacts were hashed independently with `Get-FileHash` and compared against `release/SHA256SUMS.txt`:

| Artifact | Independent SHA-256 | Matches manifest |
|---|---|---|
| `Aethon-VPN-v2.2.0-Android-arm64-v8a.apk` | `1af9b7ed803fa7847f4bacf8e4e497f2de7c9fe9ba8ea5ba4558affe98e92e9d` | PASS |
| `Aethon-VPN-v2.2.0-Android-armeabi-v7a.apk` | `3c502dac5be107a28513595f90c0567220d4a09794756fd780b00d7d4faf7bed` | PASS |
| `Aethon-VPN-v2.2.0-Android-universal.apk` | `20a909d50565ed9addd4de408507456cf27931858dc6ba8d2c2a6a908c1ff58f` | PASS |
| `Aethon-VPN-v2.2.0-Android-x86_64.apk` | `25baeef458ab5a8c608ae2867aa1f62c81978b55a309e0139324e56b2d33cea8` | PASS |
| `Aethon-VPN-v2.2.0-Windows-x64-Installer.exe` | `463cfb199f719e8b37f4d75e76eb75070833b100ecb3f1a741477b897099bb48` | PASS |
| `Aethon-VPN-v2.2.0-Windows-x64.msi` | `9dc40043468c919fe347e34eb93e66215b7ab2fc265375e0bba0bf24460b2185` | PASS |
| `Aethon-VPN-v2.2.0-Windows-x64-portable.zip` | `12b2add356ce05b83575ce76a4a9b94bbd90d877dcbc6e8df6c7d9f9e710c77c` | PASS |

**Result: 7/7 PASS.** No artifact was rebuilt or modified.

## 2. APK hash-to-filename discrepancy — root cause

**Finding:** The *original* release build report (commit `8e6ff24`, `AETHON_V2.2.0_ANDROID_FINAL_RELEASE_REPORT.md` §4) listed the four release-APK hashes with the hash column transposed relative to the filename/size columns:

| Report row (original) | Hash it printed | Hash actually belonging to that APK |
|---|---|---|
| arm64-v8a | `25baeef4…` | x86_64 APK's hash |
| armeabi-v7a | `1af9b7ed…` | arm64-v8a APK's hash |
| x86_64 | `20a909d5…` | universal APK's hash |
| Universal | `3c502dac…` | armeabi-v7a APK's hash |

**Root cause:** Documentation transposition in the hand-assembled report table only. The artifacts themselves were never misnamed and `SHA256SUMS.txt` was never wrong — the machine-generated manifest has always matched the actual files (the same four digest values appear in both; only the report's row assignment was shifted). Confirmed by independent evidence:

- The file *sizes* in the original report table were already correct per filename (only the hashes were shifted).
- Each APK contains exactly the native libraries for its own named ABI, and every embedded payload hash matches its pinned digest — an APK carrying another ABI's payload set would have failed this check.
- `aapt2` reports the correct `native-code` ABI set for each filename.

**Resolution:** Already corrected in cleanup commit `9e40c16` (the report table now matches the actual files). This audit re-verified both the corrected release report table and the cleanup report table against the independently computed hashes: **both are accurate. No further documentation or checksum corrections are necessary.**

## 3. APK identity, ABI, and signature verification

| Check | arm64-v8a | armeabi-v7a | x86_64 | universal |
|---|---|---|---|---|
| Package name | `io.github.hamvex.aethergui` | `io.github.hamvex.aethergui` | `io.github.hamvex.aethergui` | `io.github.hamvex.aethergui` |
| versionName / versionCode | 2.2.0 / 29 | 2.2.0 / 29 | 2.2.0 / 29 | 2.2.0 / 29 |
| Native ABI set (aapt2 + embedded `lib/` payload inspection) | `arm64-v8a` only | `armeabi-v7a` only | `x86_64` only | all three ABIs |
| Embedded native payload digests match pins | PASS | PASS | PASS | PASS |
| APK Signature Scheme v2 verification | PASS | PASS | PASS | PASS |
| Production signing certificate SHA-256 | `1e5a37ef…e100` | `1e5a37ef…e100` | `1e5a37ef…e100` | `1e5a37ef…e100` |

All four APKs verify with the original production release certificate `1e5a37ef9bee8f3be747f18d75fd9cadc24c302ae327d53d480cac162ca7e100` — the same identity that signed the public v2.1.1 APKs, so v2.2.0 can upgrade v2.1.1 installations. **Result: PASS.**

## 4. Final state

- Working tree clean at `9e40c16`; no application code, release binaries, or checksum files were modified by this audit.
- `release/` contains exactly the intended public distribution: the 7 verified artifacts plus `SHA256SUMS.txt`.
- Per the focused scope: SBOM regeneration, Windows deep revalidation, and full automated test runs were intentionally skipped; the prior cleanup validation records remain authoritative for those areas.

## 5. Verdict

| Audit item | Result |
|---|---|
| Independent hash verification of 7 release artifacts | PASS (7/7) |
| Manifest (`SHA256SUMS.txt`) accuracy | PASS |
| APK hash-to-filename discrepancy root-caused and resolved | PASS — documentation-only transposition, already corrected; artifacts always correct |
| APK ABI sets | PASS (4/4) |
| APK package identity and version metadata | PASS (4/4) |
| APK production signatures | PASS (4/4) |
| Documentation corrections needed now | NONE |

**Final verdict: PASS. Publication is not blocked.**
