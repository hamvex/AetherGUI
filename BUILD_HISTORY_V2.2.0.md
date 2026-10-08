# Aethon v2.2.0 Build History

This file records v2.2.0 validation runs. Historical v2.1.2 development evidence remains in the original workspace.

## v2.2.0 migration baseline

- Windows source baseline: validated Windows dev.033 worktree state.
- Android source baseline: validated Android dev.037 worktree state.
- Aether Core: official unmodified v2.3.0, upstream commit `6175b67df370ab856bcee07fe85b524031903956`.
- Android versionName: `2.2.0`; versionCode: `29`.
- Physical Android validation: NOT TESTED by instruction; no ADB or device installation used.

## v2.2.0 clean-build and automated-validation run

- Date: 2026-10-08.
- Windows: Node 26.10.0, Rust 1.97.1, Java 17.0.20, Android SDK 35, Android NDK 27.2.12479018.
- Shared/frontend tests: 38 passed / 0 failed.
- Core/native pin tests: 6 passed / 0 failed.
- Android builder/provenance tests: 15 passed / 0 failed.
- Rust tests: 145 passed / 0 failed / 1 pre-existing ignored test.
- Android clean build: 303 unit tests passed / 0 failed / 0 errors / 0 skipped; lint 0 Fatal / 0 Error / 101 warnings; all four debug APKs built and verified.
- Windows clean build: release `aether-gui.exe`, NSIS installer, and MSI package built successfully.
- Physical Android validation: NOT TESTED by instruction; no ADB or device installation used.
- Old folders: not deleted; no GitHub publication performed.

## v2.2.0 Android debug build and validation

- Date: 2026-10-08.
- Git branch: `main`; baseline commit: `32e8c5c`.
- Android versionName: `2.2.0`; versionCode: `29`.
- Aether Core: official unmodified `v2.3.0`.
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, and Universal.
- Signing identity: original production release signing identity not available; existing Android debug signing identity used only for clearly named DEBUG APKs.
- Automated unit tests: 303 passed / 0 failed / 0 errors / 0 skipped.
- Android lint: 0 Fatal / 0 Error / 101 warnings.
- APK signatures: APK Signature Scheme v2 verified for all four APKs.
- Native library SHA-256 verification: all embedded payloads matched the pinned dev.037 manifest.
- ABI verification: package metadata and embedded native libraries matched each target ABI.
- 16-KiB alignment: verified for all four APKs.
- Output location: `release/`.
- Physical Android validation: NOT TESTED by instruction; no ADB or device installation used.

## v2.2.0 Android official release build

- Date: 2026-10-08.
- Git branch: `main`; baseline commit: `ce131b5`.
- Signing identity: original production release certificate `1e5a37ef9bee8f3be747f18d75fd9cadc24c302ae327d53d480cac162ca7e100`.
- Signing material: copied from the legacy repository root into the ignored canonical `android/.android-signing/` directory; no new key generated.
- Android versionName: `2.2.0`; versionCode: `29`.
- Aether Core: official unmodified `v2.3.0`.
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, and Universal.
- Automated unit tests: 303 passed / 0 failed / 0 errors / 0 skipped.
- Android lint: 0 Fatal / 0 Error / 101 warnings.
- APK signatures: APK Signature Scheme v2 verified on all four release APKs.
- Native library SHA-256 verification: all embedded payloads matched the pinned dev.037 manifest.
- ABI verification: package metadata and embedded native libraries matched each target ABI.
- 16-KiB alignment: verified for all four release APKs.
- Output location: `release/`.
- Physical Android validation: NOT TESTED by instruction; no ADB or device installation used.

## v2.2.0 final pre-release cleanup

- Date: 2026-10-09.
- Removed gitignored temporary build outputs (`android/app/build`, `android/app/build-codex`, `android/.gradle`, `android/validation`, `validation`, `portable`, `scripts/__pycache__`, `src-tauri/gen`, empty `src-tauri/tests`) and the four interim DEBUG APKs from `release/`; regenerated `release/SHA256SUMS.txt` for the seven public v2.2.0 artifacts.
- Removed obsolete development-phase reports and unreferenced legacy screenshots from the tracked tree; corrected stale documentation references in `README.md`, `BUILD_HISTORY_V2.2.0.md`, and `AETHON_V2.2.0_ANDROID_FINAL_RELEASE_REPORT.md` (fixed the release APK hash-table alignment).
- Preserved the production Android signing identity, all pinned native inputs, the Aether Core v2.3.0 payloads, all source, tests, licenses, and the signed release artifacts; no VPN behavior, UI, protocols, or Core binaries were changed.
- Post-cleanup verification: frontend tests 38 passed / 0 failed; core/native pin tests 6 passed / 0 failed; Android builder/provenance tests 15 passed / 0 failed; Rust tests 145 passed / 0 failed / 1 pre-existing ignored; Android unit tests 303 passed / 0 failed / 0 errors / 0 skipped; all four release APK signatures verified against the production certificate; all release-manifest SHA-256 entries verified.
- Disk space recovered: approximately 3.95 GiB.
- No ADB, physical-device testing, release rebuild, GitHub publication, or Git history rewrite was performed.
