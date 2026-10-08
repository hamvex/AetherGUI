# Aethon v2.2.0 Migration Report

**Date:** 2026-10-08  
**Source workspace:** `D:\project\AetherGUI-v2.1.2`  
**Target workspace:** `D:\project\AetherGUI-v2.2.0`  
**Mission:** Clean canonical migration of the validated Windows dev.033 and Android dev.037 baselines into a standalone Aethon v2.2.0 project.

## 1. Original workspace structure

The original v2.1.2 workspace was an intentionally dirty Git worktree on branch `development/v2.1.2`, HEAD `132b842175e3e6f974c2f21274b9b92301cad590`, with a large amount of local development evidence. Its top-level structure contained:

- Source and configuration: `.github`, `android`, `scripts`, `src`, `src-tauri`, `tests`, `third-party`, `package.json`, `package-lock.json`, and project documentation.
- Historical build and validation evidence: `development-builds`, `inspection`, `work`, and numerous Markdown reports.
- Generated or local-only state: `node_modules`, `dist`, `src-tauri/target`, `android/app/build`, `android/app/build-dev`, `.gradle-home`, `.android-user-home`, `.runtime-install`, `.aether-upstream-*`, `.xray-*`, and `AETHON_QUARANTINE_PRE_V2.1.2`.
- Required runtime inputs: verified Windows sidecars and Android JNI/helper payloads, stored in the old workspace’s local build and work directories.

The worktree contained the authoritative source state for both validated platform baselines. No Git reset, checkout, clean, commit, push, or history rewrite was performed.

## 2. Final workspace structure

`D:\project\AetherGUI-v2.2.0` now contains a standalone canonical source tree:

- `.github/`
- `android/`
- `docs/`
- `native-inputs/`
- `scripts/`
- `src/`
- `src-tauri/`
- `tests/`
- `third-party/`
- `release/`
- `portable/`
- `validation/`
- Root legal, notice, build-history, SBOM, baseline, and migration documentation.

Generated build directories (`node_modules`, `dist`, `src-tauri/target`, `android/app/build`, and temporary `work`) were removed after validation so they do not become part of the source baseline. The Windows release artifacts remain under `release/`, and validation logs and extracted installer evidence remain under `validation/`.

The folder is not currently a Git repository. No Git history was copied, rewritten, committed, or pushed. The original repository and worktree remain intact.

## 3. Exact baselines used

### Windows baseline

- Baseline: **Windows dev.033**.
- Source state: the dirty `development/v2.1.2` worktree state documented by `AETHON_WINDOWS_DEV033_FINAL_REPORT.md` and `AETHON_DEV033_GOOL_TOR_ROOT_CAUSE_AUDIT.md`.
- Final retained installer from that mission:
  - `development-builds/dev.033/Aethon-v2.1.2-dev.033-Windows-x64-Installer.exe`
  - SHA-256: `e24e51ab601957448889e142808249dcd0f183cfedf2bdf82fda84339f148fb2`
- The baseline’s plain-gool IR-exit fix, truthful gool topology reporting, Tor watchdog/gate ordering fix, plain-mode HTTP 1818 contract, and cold-route TLS retry behavior are preserved in the v2.2.0 source.

### Android baseline

- Baseline: **Android dev.037**.
- Source state: the dirty `development/v2.1.2` worktree state documented by:
  - `development-builds/dev.037/source-state.json`
  - `development-builds/dev.037/build-manifest.json`
  - `development-builds/dev.037/source-snapshot.zip`
  - `AETHON_ANDROID_DEV034_FINAL_REPORT.md`
  - `AETHER_V2.3.0_ANDROID_COMPLETE_INTEGRATION_AUDIT.md`
- Source manifest SHA-256: `5edd6517d3a40db9148e8ab9718f39e21cf103d1111fcb63a21e6d682e99af34`
- Source snapshot SHA-256: `e62657c1e88a7909ae07f4bb51cf720164029689e06d7bed9062399b8735bd81`
- Final retained dev.037 ARM64 APK SHA-256: `565f5561b303e6f90ca939497bdf670c4e4019321087bc725cbb14e373fcec62`
- The baseline’s MASQUE-carrier Psiphon bootstrap allowance and privacy-chain 12-second traffic-probe budget are preserved.

The build artifacts themselves were not treated as the migrated source. They were used only as provenance references to identify the exact validated source and native input state.

## 4. Files and directories migrated

The clean v2.2.0 source tree contains:

- Windows frontend and Tauri backend source from the validated dev.033 state.
- Android Java, resources, tests, Gradle wrapper, and build configuration from the validated dev.037 state.
- Shared frontend, protocol, settings, and test source.
- Build, fetch, verification, signing, and packaging scripts.
- GitHub release workflow.
- Required legal and notice files.
- Project documentation and SBOM.
- Package and Cargo dependency manifests and lockfiles.
- Verified Windows and Android native inputs under `native-inputs/`.
- A new clean build history file for v2.2.0.

The old project’s historical source snapshots, build artifacts, physical-test evidence, Gradle caches, inspection directories, and work directories were intentionally not copied.

## 5. Files and directories intentionally excluded

The following were not migrated as source:

- `development-builds/`
- `inspection/`
- `work/`
- `node_modules/`
- `dist/`
- `src-tauri/target/`
- `android/app/build/`
- `android/app/build-dev/`
- `.gradle-home/`
- `.android-user-home/`
- `.runtime-install/`
- `.aether-upstream-*`
- `.xray-*`
- `AETHON_QUARANTINE_PRE_V2.1.2`
- Old APKs, EXEs, MSIs, archives, logs, screenshots, logcat captures, and physical-test evidence.

These remain in the original workspace for provenance and rollback. They are not required to build, test, or continue v2.2.0.

## 6. Shared-file conflicts and resolution

The Windows and Android baselines were both represented by the same current dirty worktree, so there were no unresolved branch-level conflicts. The migration reconciled shared files by preserving the validated current source and making only migration-specific changes:

- Version identity was changed from `2.1.2` and development suffixes to `2.2.0`.
- Android `versionCode` was advanced from `28` to `29`.
- Android native provenance paths were changed from old `work/...` paths to the standalone `native-inputs/android/` tree.
- Android build scripts and tests were updated to resolve native inputs from the new local path.
- Documentation and About/update UI text were updated to the new active version.
- No unrelated redesign, refactor, formatting sweep, dependency upgrade, or behavioral change was introduced.

Historical dev.033/dev.037 references remain in comments, tests, and baseline reports only as provenance and regression documentation. They are not active release identities.

## 7. Version changes

The active release identity is exactly **`2.2.0`** with no development suffix.

Changed version sites include:

- `package.json` and `package-lock.json`
- `src-tauri/Cargo.toml` and `src-tauri/Cargo.lock`
- `src-tauri/tauri.conf.json`
- `android/app/build.gradle`
- `android/app/src/main/res/values/strings.xml`
- Windows About and update UI
- `scripts/package-release.ps1`
- `BUILD_HISTORY_V2.2.0.md`

Android package identity remains `io.github.hamvex.aethergui`. No package ID was changed.

## 8. Core and native provenance

### Aether Core

Both platforms use the official, unmodified Aether Core **v2.3.0**:

- Tag: `v2.3.0`
- Source commit: `6175b67df370ab856bcee07fe85b524031903956`

Windows Core SHA-256:

- `4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1`

Android Core SHA-256:

- ARM64: `2b44db73249eab204416c4e4977151db47aee487e1b96bae3c7e729f2812680e`
- ARMv7: `d8f3865a33366831aef881685f6c2d6e85c934c70fd18c38aa920ba140bb7bfb`
- x86_64: `46735a4808500a21b5f95af340c6977cb0c7dad264fa57427903a9aaed2161f3`

### Android native inputs

The exact twelve dev.037 verified native payloads are present under `native-inputs/android/`:

- `libaether.so`
- `libhev-socks5-tunnel.so`
- `libpsiphon-tunnel-core.so`
- `liblyrebird.so`

for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

Their sizes, ELF classes, ELF machine types, and SHA-256 digests were verified against:

- `native-inputs/android/NATIVE_INPUTS.json`
- `native-inputs/android/verified-native-inputs.json`
- `native-inputs/android/verified-helper-inputs.json`

The approved manifest SHA-256 remains:

- `c8d91f3f1fd2691933675dcdeb11111f26cdb0612c02e7c2e10410e1ac44853a`

### Windows native inputs

The verified Windows sidecars are present under `native-inputs/windows/` and `src-tauri/binaries/`:

- Aether Core: `4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1`
- Xray: `15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1`
- Wintun: `e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce`
- Psiphon helper: `a6fc6094e169b61eed5fb51f7267a8ee2c648765c3e1c9a6cd88affe63ab4f29`
- Lyrebird helper: `7ccde802e4b9e9967255a63ae51cefb72418f99f15cdd7d50f8f4cb96e1105c7`

No Core binary was patched, downgraded, forked, or substituted.

## 9. Dependency audit

- `npm ci` completed successfully from `package-lock.json`.
- Rust dependencies were built with `cargo test --locked` and the production build.
- Gradle 8.10.2 was used through the project wrapper.
- Android SDK Platform 35 and NDK 27.2.12479018 were used.
- The clean Android Gradle cache initially lacked a few AndroidX artifacts. The successful offline build reused an existing generic Gradle dependency cache from the old workspace; no old source, native input, APK, signing key, or device state was used.
- No dependency versions were upgraded.
- No untracked old source dependency was required.

## 10. Independence audit

The v2.2.0 source and build scripts were searched for operational references to the old workspace. The active build and native verification paths now resolve only inside `D:\project\AetherGUI-v2.2.0`.

Remaining references to the old project are limited to:

- Historical baseline and audit reports copied for provenance.
- Comments and tests that document dev.033/dev.037 behavior.
- An optional historical dev.030 fixture check in `tests/frontend.test.mjs`, which is skipped when the old fixture is absent.

There is no operational build or runtime dependency on `D:\project\AetherGUI-v2.1.2` for source, configuration, scripts, native inputs, packaging, or testing.

## 11. Automated test results

| Suite | Result |
| --- | --- |
| Windows/frontend shared tests (`npm test`) | 38 passed / 0 failed |
| Core/native pin tests (`node tests/core-pins.test.mjs`) | 6 passed / 0 failed |
| Android builder/provenance tests (`python -m unittest`) | 15 passed / 0 failed |
| Rust/Tauri tests (`cargo test --locked`) | 145 passed / 0 failed / 1 pre-existing ignored |
| Android unit tests (`testDebugUnitTest`) | 303 passed / 0 failed / 0 errors / 0 skipped |
| Android lint (`lintDebug`) | 0 Fatal / 0 Error / 101 warnings |

The 101 Android lint warnings are pre-existing informational warnings, not migration regressions.

## 12. Android build results

The clean Android build completed successfully for all supported architectures.

Package identity:

- Package: `io.github.hamvex.aethergui`
- versionName: `2.2.0`
- versionCode: `29`
- APK Signature Scheme v2: verified
- 16-KiB zip alignment: verified
- Debug signer SHA-256: `68a8995c8e1bcb6d4bd5cc73369e29edc3f6e8bd94e1a7c3142a96e73be49d3d`

Built debug APKs:

| Architecture | Artifact | Size | SHA-256 |
| --- | --- | ---: | --- |
| Universal | `app-universal-debug.apk` | 81,139,383 | `931452a66b55a7d6602c94f287afaa4541de95b5203df8ad2e2d8898bd8382f9` |
| ARM64 | `app-arm64-v8a-debug.apk` | 31,135,255 | `967a6b63d344d38382f5c70b499d49a8c7ff3085cf01e34498545dd1a3ea8e74` |
| ARMv7 | `app-armeabi-v7a-debug.apk` | 30,414,435 | `6d02208d1a79cca56db0c1775df6e0622a4fd83e343ce1138463efb705a9a2a3` |
| x86_64 | `app-x86_64-debug.apk` | 33,732,563 | `6ee5d2c4ad33f3b836c08275ded3ac96049854fc511c058fcf6bb20ff44b5758` |

The APKs were generated and verified from the clean v2.2.0 tree, then removed from the canonical source tree after verification. Their digests are recorded above.

All embedded native libraries matched the dev.037 manifest digests for every ABI.

## 13. Windows build results

The Windows production build completed successfully.

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| NSIS installer | `Aethon-VPN-v2.2.0-Windows-x64-Installer.exe` | 32,148,545 | `463cfb199f719e8b37f4d75e76eb75070833b100ecb3f1a741477b897099bb48` |
| MSI installer | `Aethon-VPN-v2.2.0-Windows-x64.msi` | 44,097,536 | `9dc40043468c919fe347e34eb93e66215b7ab2fc265375e0bba0bf24460b2185` |
| Portable ZIP | `Aethon-VPN-v2.2.0-Windows-x64-portable.zip` | 41,987,880 | `12b2add356ce05b83575ce76a4a9b94bbd90d877dcbc6e8df6c7d9f9e710c77c` |

The built executable is `2.2.0`. The NSIS and MSI metadata identify as `2.2.0`. Embedded Aether, Xray, Wintun, Psiphon, and Lyrebird payloads were extracted and hash-verified.

The first MSI packaging attempt failed with a transient network error (`os error 10053`) while downloading the WebView2 bootstrapper. A retry completed successfully.

The Windows artifacts are unsigned because no signing certificate or signing script credentials were supplied.

## 14. Physical and runtime validation

By explicit instruction:

- No physical Android device testing was performed.
- No ADB command was run.
- No APK was installed on a device.
- No device VPN connection, speed, stress, or protocol test was repeated.

A minimal Windows runtime smoke test was performed:

- The freshly built `aether-gui.exe` was launched.
- It remained alive for five seconds.
- It was terminated by the test harness.
- No Aether, Xray, Psiphon, or Lyrebird processes remained afterward.

This smoke test did not exercise UI interaction, VPN connection, protocol behavior, or real traffic. Those remain covered only by the dev.033 and dev.037 baseline evidence and automated tests.

## 15. Known environment limitations

- Physical Android validation: **NOT TESTED by instruction**.
- ADB and device installation: **NOT USED**.
- Windows runtime behavior beyond process launch: **NOT TESTED**.
- Android release APK build: **NOT PERFORMED** because release signing credentials were not supplied.
- Windows artifacts are unsigned.
- The clean Android build reused an existing Gradle dependency cache from the old workspace. This is a cache-only dependency; source, native inputs, and artifacts came from v2.2.0. A fully cache-independent build should use a fresh online Gradle cache.
- The new folder is not a Git repository. Git-based release automation that requires a clean worktree cannot be used until a repository is initialized or the folder is attached to an existing repository.
- The original old project folder remains in place for rollback and provenance.

## 16. Final old-folder differential audit

The old v2.1.2 folder was not deleted.

High-level differential classification:

- **Already migrated:** required source, configuration, build scripts, tests, legal files, documentation, dependency manifests, and verified native inputs.
- **Generated artifacts:** old build outputs, target directories, Gradle caches, node_modules, dist, development-build binaries, and temporary work directories.
- **Historical evidence:** dev.033/dev.037 reports, build manifests, source snapshots, physical-validation logs, screenshots, and inspection evidence.
- **Rollback material:** the complete original workspace and Git worktree.
- **Obsolete or duplicate:** superseded development-build directories, duplicate source snapshots, and old temporary archives.
- **Unknown:** none identified that block the migration.

No required v2.2.0 source, configuration, script, native input, license, or build dependency remains exclusively in the old folder.

## 17. Whether old folders were deleted

No old project folder was deleted.

Deletion is blocked by the user’s explicit instruction and by the prudent rollback requirement. The old folder remains as the recovery path and historical evidence archive.

## 18. Remaining risks and recommendations

1. **Release signing**
   - Android release APKs were not built because release signing credentials are unavailable.
   - Windows installers and the portable executable are unsigned.
   - Before public release, provide release signing configuration without copying secrets into the repository.

2. **Physical Android validation**
   - No new device validation was performed, by instruction.
   - If device validation is later required, use the existing dev.037 physical evidence as the baseline and perform only the necessary new v2.2.0 checks.

3. **Gradle cache independence**
   - The successful Android build used an existing offline dependency cache.
   - Run one online build with a fresh Gradle cache to prove full dependency-cache independence.

4. **Git structure**
   - Initialize or attach the clean v2.2.0 folder to a Git repository before using Git-dependent release automation.
   - Do not rewrite or discard the old repository history.

5. **Runtime validation**
   - Windows was only smoke-tested by process launch.
   - No new VPN, protocol, or real-traffic tests were performed.

## 19. Canonical-baseline conclusion

`D:\project\AetherGUI-v2.2.0` is **safe to use as the canonical Aethon source and development baseline**.

It contains the required source, build configuration, scripts, resources, native inputs, licenses, dependency manifests, and documentation needed to continue development without the old v2.1.2 folder.

It is not yet a fully signed public release because Windows artifacts are unsigned and Android release signing was not available. Physical Android validation was intentionally skipped.
