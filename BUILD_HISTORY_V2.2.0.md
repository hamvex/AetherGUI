# Aethon v2.2.0 Build History

This file records v2.2.0 validation runs. Historical v2.1.2 development evidence remains in the original workspace and is referenced by `AETHON_V2.2.0_MIGRATION_REPORT.md`.

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
