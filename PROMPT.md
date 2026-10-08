AETHON v2.2.0 — CLEAN CANONICAL MIGRATION, VALIDATION, AND RELEASE PREPARATION

You are working on the existing Aethon/AetherGUI project.

This is a high-risk migration/cleanup task. Do not make assumptions, do not blindly copy folders, do not delete anything prematurely, and do not claim success without evidence.

==================================================
PRIMARY GOAL
==================================================

Create a new clean project folder/version:

2.2.0

From this point forward, 2.2.0 must become the single canonical, active, standalone development baseline for Aethon.

The existing 2.1.2 and any other old version folders must NOT remain operational dependencies of 2.2.0.

However:

DO NOT delete 2.1.2 or any old version folder until the new 2.2.0 project has been completely reconstructed, validated, built, tested, and proven independent.

==================================================
AUTHORITATIVE BASELINES
==================================================

The migration must preserve the exact latest validated source state for each platform.

WINDOWS BASELINE:
- Windows dev.033
- Treat the source state that produced dev.033 as the authoritative Windows baseline.
- Do NOT accidentally replace it with an older commit, older snapshot, public main branch state, or earlier development build.

ANDROID BASELINE:
- Android dev.037
- Treat the exact source state that produced dev.037 as the authoritative Android baseline.
- Android dev.037 uses the official, unmodified Aether Core v2.3.0.
- Aether Core tag: v2.3.0
- Upstream source commit:
  6175b67df370ab856bcee07fe85b524031903956
- dev.037 is the final retained Android development build.
- It passed 303 unit tests with 0 failures/errors/skips.
- Lint had 0 Fatal and 0 Error.
- It was physically validated on Samsung SM-A556E / Galaxy A55 / ARM64.
- Preserve all app-side fixes that exist in dev.037, including the MASQUE-carrier Psiphon bootstrap allowance and privacy-chain traffic probe budget.
- Do NOT downgrade, patch, fork, or substitute Aether Core v2.3.0.

IMPORTANT:
The dev.033/dev.037 build artifacts themselves are NOT what should be migrated.
They are authoritative references for identifying the exact final source/configuration state that must be preserved.

==================================================
PHASE 1 — AUDIT BEFORE CHANGING ANYTHING
==================================================

First inspect the complete existing project and repository.

Identify:

1. Current folder structure.
2. Git repository/root structure.
3. Current branch, HEAD, working tree, tracked files, untracked files, ignored files and relevant local-only files.
4. Exact source/configuration state corresponding to:
   - Windows dev.033
   - Android dev.037
5. Build manifests, source-state manifests, source snapshots, hashes or reports that can prove those baselines.
6. Shared source used by both Windows and Android.
7. Platform-specific source.
8. Required build scripts.
9. Required packaging scripts.
10. Required configuration files.
11. Required native libraries/helpers.
12. Required resources/assets.
13. Required dependency manifests/lockfiles.
14. Required Git/GitHub configuration.
15. Required signing/build configuration, without exposing or copying secrets unnecessarily.
16. Anything in 2.1.2 that the current validated source actually depends on.

Do not modify or delete anything during this audit until you understand the dependency graph.

If dev.033 or dev.037 cannot be reconstructed confidently from available provenance, STOP and report the ambiguity rather than guessing.

==================================================
PHASE 2 — CLASSIFY FILES BEFORE MIGRATION
==================================================

Classify the contents of the old project into at least:

A. REQUIRED SOURCE
B. REQUIRED BUILD/CONFIGURATION
C. REQUIRED RUNTIME/NATIVE ASSETS
D. REQUIRED PACKAGING/RELEASE INFRASTRUCTURE
E. REQUIRED LICENSE/NOTICE/LEGAL FILES
F. REQUIRED PROJECT DOCUMENTATION
G. DEVELOPMENT EVIDENCE / HISTORICAL ARTIFACTS
H. GENERATED BUILD OUTPUTS
I. TEMPORARY FILES / CACHES / LOGS
J. OBSOLETE OR DUPLICATE FILES
K. UNKNOWN — requires investigation

Only migrate material necessary for a complete, maintainable, reproducible Aethon v2.2.0 source project.

Do NOT blindly copy the entire 2.1.2 folder.

==================================================
PHASE 3 — CREATE CLEAN 2.2.0 PROJECT
==================================================

Create the new 2.2.0 folder.

Populate it using the validated final source states:

- Windows = dev.033
- Android = dev.037

The resulting project must contain everything actually necessary to:

- develop Aethon
- build Windows
- build Android
- package releases
- run tests
- preserve required runtime behavior
- preserve required native dependency integrity
- continue future development from 2.2.0

Shared files must be reconciled carefully.

If Windows dev.033 and Android dev.037 changed the same shared file differently, DO NOT arbitrarily choose one.

Compare the changes, determine why they differ, and merge them correctly so both platforms retain their validated behavior.

Do not introduce unrelated redesigns, refactors, optimizations, formatting sweeps, dependency upgrades, or behavioral changes during this migration.

==================================================
WHAT MUST NOT BE COPIED JUST BECAUSE IT EXISTS
==================================================

Do not populate 2.2.0 with unnecessary historical development material such as:

- old APKs
- old EXEs/MSIs/installers
- previous development-build binaries
- temporary build directories
- Gradle caches
- target/build outputs
- temporary downloads
- runtime logs
- logcat dumps
- screenshots
- physical-test captures
- obsolete inspection directories
- temporary work directories
- duplicate source snapshots
- old generated archives
- superseded development artifacts

Historical evidence may remain outside the clean 2.2.0 source tree until cleanup is approved.

Do not destroy provenance merely to make the new folder smaller.

==================================================
PHASE 4 — VERSION MIGRATION TO FINAL 2.2.0
==================================================

After reconstructing the correct source baseline, migrate application versioning from the old development identity to the new final application version:

2.2.0

The final release identity must NOT contain:

dev.033
dev.037
-dev
-codex
2.1.2

except where an old version is intentionally referenced for migration logic, compatibility tests, changelog/history, or provenance.

Audit every version source instead of changing only visible UI text.

Check at minimum:

- package metadata
- Android Gradle versionName/versionCode
- Windows/Tauri/package metadata
- installer metadata
- app About/version UI
- update logic
- packaging scripts
- artifact naming
- release scripts
- tests/fixtures that intentionally encode versions
- documentation where active version is declared

Do not blindly replace historical compatibility fixtures.

The user-facing/release version must be exactly:

2.2.0

with no development suffix.

Preserve Android package/application identity and upgrade compatibility unless a change is explicitly required by existing project design.

Do not change package IDs merely because the version changes.

==================================================
PHASE 5 — CORE AND NATIVE INTEGRITY
==================================================

Android must retain official unmodified Aether Core v2.3.0.

Verify the complete native input chain for all supported Android ABIs:

- arm64-v8a
- armeabi-v7a
- x86_64

Preserve all required helpers used by the validated Android baseline, including where applicable:

- libaether.so
- libhev-socks5-tunnel.so
- libpsiphon-tunnel-core.so
- liblyrebird.so

Verify hashes/provenance against the existing approved pins/manifests.

Do not download or substitute a different binary merely because another copy is easier to obtain.

Do not silently fall back to an older Aether Core.

Do not patch the Core.

==================================================
PHASE 6 — INDEPENDENCE AUDIT
==================================================

Before touching the old folders, prove that 2.2.0 is self-contained.

Search the complete 2.2.0 tree and its scripts/configuration for dependencies or path references to:

- 2.1.2
- old version folders
- development-builds from the old workspace
- old work/inspection directories
- absolute paths pointing into the old project
- source snapshots outside 2.2.0
- old native-library staging paths
- old temporary build directories

Distinguish harmless historical/version-migration references from operational dependencies.

There must be ZERO operational build/runtime dependency on 2.1.2.

==================================================
PHASE 7 — CLEAN BUILD FROM 2.2.0
==================================================

Perform builds from the new 2.2.0 folder itself.

Do not build using source files, binaries, caches, scripts or native inputs that secretly resolve from 2.1.2.

Run the appropriate clean build/test pipelines for both platforms.

ANDROID:
- clean build
- unit tests
- lint
- release/final build as appropriate
- build all currently supported APK architectures
- verify APK metadata
- verify package identity
- verify versionName/versionCode
- verify signatures
- verify alignment
- verify embedded native libraries
- verify Core v2.3.0 identity and hashes

WINDOWS:
- run relevant frontend/shared tests
- run Rust/Tauri tests
- clean production build
- create the normal Windows release packages/installers
- verify application/installer version metadata
- verify embedded Aether/native assets
- verify that dev.033 fixes/behavior remain present

Do not hide warnings or failures.

==================================================
PHASE 8 — REGRESSION VALIDATION
==================================================

Compare 2.2.0 against the authoritative baselines.

Confirm that the migration did not accidentally remove functionality from:

Windows dev.033

or:

Android dev.037

For Android, specifically preserve the validated Aether v2.3.0 integration, settings/migrations, topology behavior, proxy behavior, Psiphon/Tor integration, Gool modes, Core integrity checks, lifecycle/cleanup logic, and the final timing fixes.

Known environment-dependent failures must not be misreported as application regressions or fake PASS results.

Never use silent fallback to make a test pass.

==================================================
PHASE 9 — PHYSICAL AND RUNTIME TESTING
==================================================

If the previously used Android physical device is available, install the newly built 2.2.0 APK as an upgrade where appropriate and verify:

- successful install
- successful launch
- preserved upgrade compatibility
- VPN permission/state behavior
- connection
- disconnect
- reconnect
- real traffic
- no orphan Core/helper processes/listeners
- basic validated protocol paths
- settings persistence
- Reset Defaults behavior

Do not erase user/device data merely to make testing easier unless absolutely necessary and explicitly justified.

For Windows, perform available runtime smoke tests of the freshly built 2.2.0 application and confirm the dev.033 baseline behavior remains intact.

If hardware/network conditions prevent a test, classify it accurately as NOT TESTED or ENVIRONMENT-BLOCKED.

Never fabricate PASS.

==================================================
PHASE 10 — CLEANUP GATE
==================================================

DO NOT delete 2.1.2 or any older version folder until ALL of the following are true:

1. 2.2.0 exists.
2. Required dev.033 Windows source is preserved.
3. Required dev.037 Android source is preserved.
4. Android Aether Core v2.3.0 provenance is verified.
5. 2.2.0 builds independently.
6. Required automated tests pass.
7. Windows build succeeds.
8. Android build succeeds.
9. Produced artifacts identify as 2.2.0 without dev suffixes.
10. No operational reference to 2.1.2 remains.
11. Required licenses/notices are present.
12. Required build/release scripts are present.
13. Required native inputs are present and verified.
14. Git/project structure is sane.
15. No important source exists only in the old folder.
16. A rollback/recovery path exists until migration validation is complete.

If ANY item fails:

DO NOT DELETE THE OLD FOLDERS.

Fix the migration if safe, or stop and report the blocker.

==================================================
PHASE 11 — OLD FOLDER REMOVAL
==================================================

Only after the cleanup gate passes:

Perform a final differential inventory between 2.1.2 and 2.2.0.

Before deletion, explicitly identify anything unique in 2.1.2.

For every unique item, classify it as:

- intentionally obsolete
- generated artifact
- historical evidence
- duplicate
- already migrated
- required and therefore MUST be migrated before deletion

Never delete an UNKNOWN item.

Only when there is no required project source/configuration/runtime dependency left exclusively in 2.1.2 may the obsolete version folders be removed.

Do not delete the Git repository/history itself.

Do not rewrite Git history.

Do not delete remote branches/releases/tags as part of this task.

Do not publish anything to GitHub unless separately instructed.

==================================================
GIT SAFETY
==================================================

Preserve repository history and traceability.

Before destructive cleanup, record enough information to identify the pre-migration state.

Do not use destructive Git operations such as:

git reset --hard
git clean -fdx
force push
history rewrite

unless explicitly authorized.

Do not discard unrelated user work.

Do not automatically commit or push unless that is already explicitly required by the user's current workflow.

==================================================
SECURITY
==================================================

Do not expose secrets, signing passwords, private keys, tokens, credentials or private configuration in reports/logs.

Do not copy secret material into the repository just to make the project "self-contained."

Preserve the project's existing secure signing approach.

Do not weaken Core integrity checks, signature checks, TLS/security behavior or privacy protections.

==================================================
FINAL PROJECT STATE
==================================================

At completion, the intended state is:

2.2.0/
    canonical Aethon source
    Windows source based on dev.033
    Android source based on dev.037
    required shared source
    required build configuration
    required scripts
    required resources
    required native inputs/pins
    required legal/license files
    required dependency manifests/lockfiles
    required project documentation

The 2.2.0 folder must be suitable as the sole starting point for the NEXT Aethon update.

A future developer must not need the old 2.1.2 folder to understand, build, run, test or continue the project.

==================================================
FINAL REPORT
==================================================

Create a detailed report inside the new 2.2.0 project:

AETHON_V2.2.0_MIGRATION_REPORT.md

The report must include:

- original workspace structure
- final workspace structure
- exact Windows baseline used
- exact Android baseline used
- evidence proving dev.033/dev.037 source selection
- files/directories migrated
- files/directories intentionally excluded
- shared-file conflicts and how they were resolved
- version changes
- Core/native provenance
- dependency audit
- independence audit
- automated test results
- Android build results
- Windows build results
- physical/runtime validation results
- known environment limitations
- final old-folder differential audit
- whether old folders were deleted
- exact reason if deletion was blocked
- remaining risks or recommendations
- confirmation whether 2.2.0 is safe to use as the canonical baseline

Also produce a concise final console summary.

==================================================
MANDATORY STOP CONDITIONS
==================================================

STOP instead of guessing or deleting if:

- the exact dev.033 Windows source cannot be established;
- the exact dev.037 Android source cannot be established;
- a required source/config file exists only in an old folder and its purpose is unclear;
- 2.2.0 still depends operationally on 2.1.2;
- either platform cannot be built from 2.2.0;
- Aether Core/native provenance cannot be verified;
- migration causes an unexplained regression;
- deleting old folders could destroy unique required work;
- any destructive operation would risk unrelated user work.

Correctness and preservation are more important than cleanup speed.

Do the work completely, but make destructive cleanup the LAST step.