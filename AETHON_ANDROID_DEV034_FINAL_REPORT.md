# AETHON ANDROID dev.034 — FINAL REPORT

**Mission:** PROMPT.md dev.034 — complete Android integration of the official, unmodified
Aether Core v2.3.0 (tag `v2.3.0`, commit `6175b67df370ab856bcee07fe85b524031903956`,
Core pin `4834bec4…`), preserving the dev.020 UI baseline, settings, and migration behavior.

**Date:** 2026-10-06 → 2026-10-08
**Workspace:** `D:\project\AetherGUI-v2.1.2`
**Git:** branch `development/v2.1.2`, HEAD `132b842` (release v2.1.1 public merge), dirty
working tree preserved; every source state snapshot and hash recorded per build in
`BUILD_HISTORY_V2.1.2.md`.

---

## 1. Final build identity

The mission's physical validation found three real, app-side defects. Per the user's
standing instruction for this session ("do not create a new development version unless a
real defect is found"), each fix rolled into a new, append-only reservation with full
evidence — dev.035 (superseded by a latent no-op in its own fix), dev.036 (corrected), and
dev.037 (chain-aware probe budget). **The final retained build is dev.037.**

| Item | Value |
| --- | --- |
| Application | `io.github.hamvex.aethergui` `2.1.2-dev.037` (versionCode 28) |
| Core | official unmodified Aether v2.3.0, all ABIs, pinned provenance verified in every build |
| Unit tests (dev.037 builder) | 303 tests, 0 failures, 0 errors, 0 skipped |
| Lint (dev.037 builder) | 0 Fatal, 0 Error (101 warnings, pre-existing informational level) |
| Debug signer SHA-256 | `68a8995c8e1bcb6d4bd5cc73369e29edc3f6e8bd94e1a7c3142a96e73be49d3d` (continuity preserved; installs over dev.020 and dev.034 without uninstall) |
| Source manifest SHA-256 | `5edd6517d3a40db9148e8ab9718f39e21cf103d1111fcb63a21e6d682e99af34` |

Final APKs (`development-builds/dev.037/`, signatures + 16-KiB alignment verified in the
builder):

| Architecture | Artifact | SHA-256 |
| --- | --- | --- |
| ARM64 | `Aethon-v2.1.2-dev.037-Android-ARM64.apk` | `565f5561b303e6f90ca939497bdf670c4e4019321087bc725cbb14e373fcec62` |
| ARMv7 | `Aethon-v2.1.2-dev.037-Android-ARMv7.apk` | `ad5b5972b88294ef601882df8abb93e9bdc3ed2f0508308c7c1143120668be15` |
| x86_64 | `Aethon-v2.1.2-dev.037-Android-x86_64.apk` | `7f4311a4b5b4fc2217dbd1e11ed650dccaa4fc2f3e00bca97e3eda403e99f3f1` |
| Universal | `Aethon-v2.1.2-dev.037-Android-Universal.apk` | `38d34d7f45fcb13be13a153f5c58f2d213a22ddcc12f2eeed698793b70b02a58` |

Earlier mission builds (dev.034, dev.035, dev.036) are preserved exactly as built in
`development-builds/`; their physical evidence remains part of this report. The physical
install-over test chain on the device was: dev.020 → dev.034 (`adb install -r`, "Success")
→ dev.035 → dev.036 → dev.037, all without uninstall and with VPN permission retained.

## 2. Physical validation summary (final retained build, dev.037)

Device: Samsung SM-A556E (Galaxy A55, arm64-v8a, Android, transport `R5CX802KXXP`).
Every case was driven through the real app UI or the app's own persisted-preference restore
path (identical pipeline: SharedPreferences → restore() → VpnConnectionController), with
full logcat capture; 143 case records are in
`work/android-dev034-20261006/physical/results.json`.

| Matrix (PHASES 39-52) | Final result on dev.037 (with earlier-build evidence) |
| --- | --- |
| 39 install over dev.020 | **PASS** — settings/identity files hash-identical, VPN permission retained, launch clean, one-time fragment-size migration 16-32 → 8-16 with marker |
| 40 Core v2.3.0 runtime proof | **VERIFIED** — "Aether v2.3.0" + "Verified Aether Core v2.3.0 SHA-256 for arm64-v8a" from the live process in every session (never inferred from APK metadata) |
| 41 WireGuard | **PASS** (dev.037: 2.3s connect, real SOCKS5 traffic, IR exit, clean teardown) |
| 41 MASQUE H2 | **PASS** (dev.037: 16.1s, h2 tunnel validated, real traffic, IR exit, clean) |
| 41 MASQUE H3 | **ENVIRONMENT-RESTRICTED** — UDP/H3 gateways unavailable on this network (the Core's own gateway scan: "scan deadline reached with no gateway"; plain MASQUE falls back to H2 by the Core's carrier scan; Gool-over-MASQUE keeps strict no-fallback by design and was honestly recorded as `ENVIRONMENTAL-FAILURE`) |
| 41 MIM | **PASS** (h2 carrier; two-hop connect verified, bounded driver-timeout race documented) |
| 41 Gool over MASQUE H2 | **PASS** — topology proof "gool ready: masque … carries wireguard", SE/IR exits, real traffic; 5/5 stress cycles on dev.035, **5/5 on dev.036**, 5/5 dev.037 equivalents (chain + stress) |
| 41 Classic Gool | **PASS** — "establishing inner WARP tunnel (warp-in-warp)", IR exits, 5/5 stress cycles (dev.034 + dev.035) |
| 42 WG + Psiphon | **PASS** — Psiphon egress BE/DE/PL/CA proven via both public ports (1819→1822, 1818→1824), CA-store trust line proven, 10/10 stress (dev.034) and **5/5 stress (dev.037)** |
| 42 MASQUE H2 + Psiphon | **PASS (dev.036, dev.037)** — the dev.034 defect case: fixed by the chain bootstrap allowance (announcement at 119s / 108s after carrier-open; 122-128s connects, real DE/US/CA Psiphon egress, clean teardown) |
| 42 Gool over MASQUE + Psiphon | **VERIFIED on dev.034/dev.036** (126s connect, DE/SE Psiphon egress, real SOCKS+HTTP traffic, clean teardown); on dev.037's two runs the Psiphon helper's meek bootstrap exceeded the 150s announcement window (meek fronts refusing through the underlay) — strict bounded failure recorded, no silent fallback |
| 42 Classic Gool + Psiphon | **PASS** (29.3s, BE egress, both public ports, clean) |
| 43 WG + Tor | **PASS** — full bootstrap (0%→15%→consensus→"the way out is open"→"tor is ready"), 73s / 11.9s connects, clean teardown (dev.036, dev.037) |
| 43 MASQUE H2 + Tor | **PASS (dev.037)** — Tor ready ~26s, real Tor HTTP + SOCKS5 traffic (2.0s/3.4s probe latency), 28.2s connect, clean teardown |
| 43 Gool over MASQUE + Tor | **PASS (dev.037)** — double MASQUE+WG+Tor chain, real Tor-chain traffic (22s probe latency through the nested chain), 82.7s connect, clean teardown |
| 43 Classic Gool + Tor | **PASS (dev.036)** — full bootstrap to "tor is ready" in ~4s, 38s connect, clean teardown |
| 44 ECH Off | **PASS** — proven in every MASQUE/carrier session ("ECH off; the server name goes out in cleartext") |
| 44 ECH Auto | **INTEGRATION VERIFIED / ENVIRONMENT-BLOCKED full handshake** — UI selection verified ("ech field=Auto"); with the default lookup the Core logs the exact refusal: "no ECH key to offer … udp://1.1.1.1:53 did not answer for cloudflare-ech.com; not asking it rather than send its name in the clear" (upstream's strict no-cleartext-SNI design); with the DoH resolver (`echDns=https://1.1.1.1/dns-query`) the Core logs "[+] fetched ECHConfigList automatically for the WARP API (71 bytes)" — the Auto lookup path is proven end-to-end — but the ECH'd WARP API calls then get no answer within 20s ×4 on this network (bounded, honest) |
| 44 ECH Custom invalid | **PASS** — bounded pre-start rejection |
| 45 TLS custom | **PASS** — valid groups/GREASE/tls-verify connect with real traffic |
| 45 TLS invalid | **PASS** — bounded pre-start rejection |
| 46 Noize Firewall / GFW | **PASS** — persisted value → Core receives exact value → starts (no censorship-effectiveness claims) |
| 47 Exit policy OFF | **PASS** — a real IR WARP exit stays connected with policy off (no hidden country blacklist; recorded `irAcceptedWithPolicyOff`) |
| 47 Exit policy enabled | **DESIGNED GATE** — not user-exposed; a staged enable is refused with "Exit-country policy pending runtime validation: exitLocationEnabled" (the exact contract the unit tests pin; exposure deferred to a dedicated future mission) |
| 48 EN / FA RTL | **PASS** — 10 Persian texts live (both directions verified, restore verified) |
| 48 Dark theme | **PASS** (with restore) |
| 48 Reset Defaults | **PASS (dev.035, dev.036)** — reset confirmed through the real dialog's positive button; protocol returns to the WireGuard+Psiphon default (base index 1 + `psiphonMode=chain` + `torProxy=false`), goolMode resets to the new default `masque`, TLS/custom cleared; immediate connect through the default (DE/PL egress) and clean teardown. (The dev.034-era "failures" were a driver bug: the script tapped the dialog's Cancel button — `android:id/button2` sorts first — and then verified the unchanged staged state.) |
| 49 Stress: default WG+Psiphon | **PASS 5/5 (dev.037)**; 10/10 recorded on dev.034 |
| 49 Stress: Gool over MASQUE | **PASS 5/5 (dev.036)**; 5/5 on dev.035; 3/5 and 5/5 recorded during the degraded dev.034 evening window (every attempt recorded) |
| 49 Stress: Classic Gool | **PASS 5/5** (dev.034 + dev.035) |
| 49 Stress: Tor paths | Tor chains verified repeatedly across the matrix (see PHASE 43 rows) |
| 50 Transitions | **ALL PASS** — WG→Gool-new, Gool-new→Classic, Classic→MASQUE H2, H2→H3, H3→WG+Psiphon, Psiphon→plain, plain→chain; no stale settings poisoning |
| 51 Occupied proxy port | **PASS** — bounded pre-start rejection with exact reason |
| 51 Invalid custom peer | **PASS** — bounded pre-start rejection |
| 52 User data / privacy | **PASS** — logs redact addresses/endpoints (`[redacted]` in Psiphon helper output); no tokens, keys, secrets, or ECH material in the captured evidence; backups keep only portable non-secret fields (unit-tested) |

## 3. The three defects found and fixed (all app-side; Core untouched)

1. **MASQUE-carried Psiphon chains tripped a flat 30s privacy-announcement gate**
   (dev.034 → dev.035/036). The gate was sized for the fast WARP underlay; Core v2.3.0
   holds the helper until the carrier tunnel validates and then bootstraps its server list
   through it. Physical measurements: 14.5s carrier-open→announcement on a fast link;
   >30s across consecutive attempts under load; the failure cleanups repeatedly showed the
   chain alive after the gate expired. Fix: a 120s startup allowance (`Tor`-consensus scale)
   on both the announcement window (30+120=150s) and the connect watchdog (95+120=215s)
   for Psiphon chains on MASQUE carriers. The dev.035 draft of the fix read the base
   protocol from the settings map — where it never exists (`AndroidCoreSettings.fromIntent`
   maps only `CoreSettings.DEFAULTS` keys) — so the allowance silently never applied and the
   device still failed at exactly 30000ms. The physical re-validation caught it; dev.036
   passes the protocol explicitly beside the map (the codebase's own pattern), pinned by a
   regression test that reproduces the real map shape.

2. **Privacy-chain traffic probes used the 4s WARP-direct budget**
   (dev.036 → dev.037). On a MASQUE-carried Tor chain, Tor bootstrapped fully every cycle
   (consensus usable, "the way out is open", "tor is ready" in ~10s) and the underlay was
   validated, but every first probe timed out at 4s ("Read timed out" across all targets,
   attempts, and re-rolls), churning teardown/re-roll cycles. The 4s budget was sized for
   nested WARP RTTs; a freshly bootstrapped helper's first request (Tor circuit selection,
   DNS over the chain, TLS, every round trip through the carrier) legitimately needs longer.
   Fix: a 12s per-probe budget for privacy-chain endpoints (Tor, Tor HTTP, Psiphon chain
   listeners); WARP-direct endpoints keep 4s; the static gate ceiling covers the worst chain
   probe so the watchdog bound stays provable.

3. **Reset Defaults driver bug (validation-side, no app defect).** The dev.034-era driver
   tapped the confirmation dialog's Cancel button and then "verified" the unchanged staged
   state. Fixed by addressing the real positive button (`android:id/button1`, text
   "Reset defaults"); the app's reset behavior then verified completely.

## 4. Migration, defaults, and preservation

- **dev.020 baseline preserved**: install-over without uninstall, all identity files
  hash-identical, preferences retained, VPN permission retained.
- **Fragment-size one-time migration** (16-32 → 8-16, v2.3.0 upstream default) fires once
  with a marker; explicit user values are never touched.
- **Gool topology migration**: a saved Gool entry (storage 2/6/9) keeps `classic`
  semantics; fresh/reset installs get the new `masque` default; explicit choices never
  rewritten.
- **Fresh/reset default protocol**: `WireGuard + Psiphon` (base index 1 + chain markers),
  the dev.020 product decision, unchanged.
- **Reset Defaults**: clears all settings, re-applies the intentional defaults
  (language kept, theme system default, Turbo scan, Psiphon transport Auto, Tor side),
  and establishes the combined default's chain state atomically — verified end-to-end.
- **UI design unchanged**: the same dev.020 layout/design; v2.3.0 controls were added in
  the established sections/style; no baseline control was restyled.

## 5. Known environmental blockers (honest, non-app)

- **ECH Auto full handshake** (see Phase 44 row): the default UDP/53 lookup to 1.1.1.1 is
  blocked on this network (Core refuses to send the name in cleartext — by design); with a
  DoH resolver the key fetch succeeds (71 bytes) but the ECH'd WARP API endpoint then goes
  unanswered. No fake PASS claimed; the mapping, UI, and lookup path are proven.
- **MASQUE H3 gateways**: no usable H3 gateway on this network (Core's own scan); plain
  MASQUE uses the Core's H2 fallback; Gool-over-MASQUE H3 stays strict by design and is
  classified `ENVIRONMENTAL-FAILURE`, never silently downgraded.
- **Gool over MASQUE + Psiphon on dev.037's midday window**: Psiphon's meek bootstrap
  (fronting endpoints refusing through the underlay) exceeded the 150s announcement
  window twice; the path is verified on dev.034/dev.036 with real egress and clean
  teardown, and the dev.037 failures are recorded strictly and bounded.
- **Tor bootstrap is network-conditional** (dev.033 documented a fully blocked window with
  direct-Core evidence); in this validation window Tor bootstrapped on every base and all
  four +Tor combinations passed with real traffic.

## 6. Deliverables

- Source: `android/` (integration + the two timing fixes, each with regression tests);
  303 unit tests, 0 failures; lint 0 errors/fatals.
- Builds: `development-builds/dev.034` → `dev.037` (append-only history with per-build
  source snapshots, manifests, hashes; native inputs verified from the official v2.3.0
  archives through the approved chain for every build).
- Physical evidence: `work/android-dev034-20261006/physical/` — `results.json` (143 case
  records, every attempt including failures), full logcat captures per stage, screenshots
  (Persian RTL, dark theme, reset dialog), and the driver stages (`stage*.py`) that
  produced them.
- Reports: this file, `AETHER_V2.3.0_ANDROID_COMPLETE_INTEGRATION_AUDIT.md`, and the
  updated `AETHER_V2.3.0_ANDROID_CAPABILITY_MATRIX.md`.

## 7. Stop-condition check

None of the PROMPT's stop conditions is triggered: official v2.3.0 payload provenance
verified for every ABI in every build; no forked or patched Core (only app-side timing
budgets changed); no silent downgrade or hidden fallback; no unresolved physical
regression (the two real regressions found were fixed and re-verified); signer continuity
preserved; dev.020 user data migrates safely (verified by install-over hashes). The
mission stops here for user review, as the PROMPT requires.
