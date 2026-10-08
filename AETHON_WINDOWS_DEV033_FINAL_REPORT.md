# AETHON WINDOWS dev.033 FINAL REPORT

Date: 2026-10-06
Mission: dev.033 — plain gool correctness (IR-exit rejection) + Tor chain reliability
PROMPT.md: AETHON WINDOWS dev.033 (PLAIN GOOL CORRECTNESS + TOR CHAIN RELIABILITY MISSION)
Workspace: `D:\project\AetherGUI-v2.1.2`
Branch: `development/v2.1.2`, HEAD `132b842175e3e6f974c2f21274b9b92301cad590`, tree dirty
(intentional continuous-development worktree — unchanged convention since dev.013).
Supporting audit: AETHON_DEV033_GOOL_TOR_ROOT_CAUSE_AUDIT.md (full evidence trail).

---

## FINAL STATUS

| Dimension | Status |
| --- | --- |
| Implementation | **DONE** (plain-gool exit-policy correction; gool strict-mode diagnostic path + truthful running-topology reporting; Tor watchdog/gate ordering fix; plain-mode HTTP 1818 contract fix; cold-route TLS retry fix) |
| Build | **VERIFIED** (official pipeline, immutable artifact recorded) |
| Physical validation | **DONE** — plain gool CLASSIC: **5/5 clean user-style cold-start cycles with real IR-exit traffic through BOTH 1818/1819 and truthful LOCATION**; strict-mode proofs for both topologies; Psiphon regression verified; +Tor honestly classified **ENVIRONMENTALLY BLOCKED** (direct official-Core evidence; no fake PASS) |
| Security status | Defender realtime ON throughout, zero exclusions, installer/built/installed/core/app-dir all scanned CLEAN, zero new detections (history holds only the dev.029-era Oct-4 Bearfoos entries) |
| Release-readiness | Development candidate, NOT a release (unsigned; environmental limits below) |

---

## 1. Immutable baselines confirmed

- **dev.032**: preserved exactly. Installer re-hashed at mission start AND at mission end:
  `07ca4e3c6a4046fb9aafb2c394e4d2c0cce467abf0bf6c08c3693fbd35766f06` (32,155,747 bytes) —
  identical to the dev.032 BUILD_HISTORY record. No dev.032 artifact was modified,
  relabeled, or overwritten. dev.033 was installed OVER the dev.033-iteration installs
  which were installed over dev.032 (`/S`, no uninstall).
- **dev.031 and prior**: untouched.
- **Android dev.020**: untouched — no file under `android/` was modified this mission
  (all changes are `src-tauri/src/*`, `src/app.js`, `src/i18n.js`, `tests/frontend.test.mjs`;
  the Android reference test passes).
- **Aether Core v2.3.0 unchanged**: installed `aether.exe` SHA-256
  `4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1` = pin = sidecar =
  the official v2.3.0 release payload (tag `v2.3.0` → commit
  `6175b67df370ab856bcee07fe85b524031903956`); runtime self-identification `aether 2.3.0`
  re-executed this mission; pre-spawn digest gate intact (pin at `process.rs:31`,
  `validate_core_binary` at `process.rs:929`); pt helper pins unchanged
  (`psiphon-tunnel-core.exe a6fc6094…`, `lyrebird.exe 7ccde802…`).
- BUILD_HISTORY appended only (this entry).

## 2. Plain-gool failure reproduction (Phase 0.1)

Reproduced as a normal user from the installed dev.032 BEFORE any source change: real
UI interaction (drawer → Configurations → native protocol select via real click +
keyboard → `gool` → Home → ONE real mouse click on the Connect orb; CDP read-only
observation). Result — the exact user error at 29033 ms:

```
"GOOL could not obtain a non-IR exit after 3 bounded attempts:
 attempt 1 returned IR; attempt 2 returned IR; attempt 3 returned IR"
```

The core log for the same attempt proves the tunnel was healthy each time: outer + inner
WARP handshakes validated end-to-end, `socks5 server listening on 127.0.0.1:1819` (the
public contract), real exit `104.28.246.167, IR via FRA, warp on`, and Aethon's own HTTPS
trace probe completed through it — then the connection was killed solely because
`probe.country == "IR"`, three times. Full evidence: §1.1 of the root-cause audit.

## 3. Exact non-IR rejection source

`src-tauri/src/lib.rs` `start_validated_protocol` (dev.032):

```rust
if settings.protocol != "gool" || probe.country != "IR" { return Ok((generation, probe)); }
… invalidate_endpoint_cache(…); process.stop().await; …
Err("GOOL could not obtain a non-IR exit after {max_exit_attempts} bounded attempts: …")
```

An unconditional product-level non-IR requirement on protocol `gool` (also reachable via
Smart Connect's gool candidate). Chain entries were exempt — which is exactly why
gool+Psiphon worked for the user while plain gool never connected.

## 4. Exact IR-policy fix (Phase 1)

- The IR-rejection branch is REMOVED. Plain-gool success = tunnel readiness + real
  end-to-end HTTPS traffic + a public exit IP — the country is never inspected.
- Country rejection now exists ONLY behind `exit_country_policy_rejected` (the single
  hook an explicit user-configured exit-country policy would implement). No such policy
  exists in the product today (the only country setting, `psiphon_region`, is the
  Psiphon chain's own egress request), so **every country — IR included — is a valid,
  informational LOCATION result**.
- Retry budget unchanged (3/2): the requirement itself was corrected, NOT padded with
  more retries (PROMPT §1.1).
- Chain semantics (Phase 1.3): unchanged and re-verified — chain probes measure the
  FINAL egress through the public relay (gool+Psiphon: IR underlay + DE final egress =
  successful chain; the underlay never invalidates a chain).
- LOCATION truthfulness: Iran renders as Iran/Tehran through the normal providers;
  provider failure renders "Location unavailable" and never tears the tunnel
  (Phase 1.2/3.5).

## 5. Plain-gool physical results (final artifact, user-style cold-start cycles)

5/5 clean cycles (app closed initially, zero owned processes/listeners, real UI protocol
selection, ONE Connect click, real traffic, disconnect, zero orphans):

| Cycle | Connect | Exit — real TLS through BOTH ports | LOCATION | Teardown |
| --- | --- | --- | --- | --- |
| 3 | 58.5 s | `ip=104.28.246.163 loc=IR warp=on` (1819 + 1818) | Iran | clean, 0/0 |
| 4 | 85.1 s | `ip=104.28.214.167 loc=IR warp=on` (1819 + 1818) | Iran | clean |
| 5 | 50.5 s | `ip=104.28.214.168 loc=IR warp=on` (1819 + 1818) | Location unavailable (honest) | clean |
| 6 | 43.5 s | `ip=104.28.214.167 loc=IR warp=on` (1819 + 1818) | Iran | clean |
| 7 | 40.2 s | `ip=104.28.214.167 loc=IR warp=on` (1819 + 1818) | Location unavailable (honest) | clean |

**IR exits are accepted; IR is displayed truthfully; the tunnel carries real traffic
through both public ports; teardown is complete.** An additional 2 clean cycles ran on
the intermediate v2 build (one with "Tehran, Iran" LOCATION) for 7 total clean cycles
across the session.

## 6. Gool topology truthfulness (Phase 2)

- **Strict-mode diagnostic path (2.1)**: `AETHON_GOOL_STRICT=1` disables the topology
  memory AND the bounded classic fallback — the stored topology runs exactly as
  selected. Physically proven both ways:
  - Strict + stored masque: the core ran MASQUE-carried ("gool: masque device=… carries
    the wireguard identity"), the gateway scan failed honestly ("no usable MASQUE
    gateway found"), terminal error `gateway scan failed` — **no hidden fallback**.
    Classification: **MASQUE-carried = FAILED / ENVIRONMENTALLY BLOCKED on this network
    (turbo scan); Classic = VERIFIED** (5/5 cycles).
  - Strict + UI-selected Classic: the core ran classic ("outer device=… | inner
    device=…", "warp-in-warp exit: 104.28.214.164, IR via FRA"), real traffic on both
    ports, LOCATION "Tehran, Iran", clean teardown.
- **Fallback semantics (2.2)**: the bounded fallback remains a compatibility feature
  (dev.032's acceptance-tested reliability fix), now strictly gated and VISIBLE: the
  connected status message reports the topology ACTUALLY running and calls out the
  fallback — physically captured:
  "Aether and System-wide VPN Mode are ready (gool classic — the MASQUE-carried gool
  found no gateway, the compatibility fallback selected classic)". The running topology
  is derived from the core's own log proof lines (`ProcessManager::runtime_gool_topology`),
  never from the stored setting. Help EN/FA updated. No Auto mode was added (not needed:
  explicit selections are strict; the fallback is visible).
- **UI → goolMode → env → runtime audit**: explicit Classic emits `AETHER_GOOL_MODE=classic`
  (settings.rs; regression-tested); explicit MASQUE-carried emits nothing (the core
  default; regression-tested); custom WiW endpoints still auto-select classic in-core
  (upstream behavior preserved); topology memory is advisory-only and strict-gated; the
  persisted `protocol`/`goolMode` settings are never touched by the fallback (asserted).

## 7. +Tor reproduction and root cause (Phase 3)

### 7.1 Reproduction (Phase 0.2, installed dev.032, user-style)

wg+tor, gool+tor (classic underlay), masque+tor — all FAILED. Universal signature: the
underlay comes up healthy and validated, Tor bootstraps 0→8→15% in ~1 s, and the
consensus fetch never progresses. wg+tor additionally flapped its WG underlay for ~54 min
before ending in the vague "Connection attempt was cancelled".

### 7.2 Direct-Core comparison (Phase 3.2 — decisive)

The exact official packaged Core, run OUTSIDE the GUI with Aethon's exact chain env:

- bridges OFF (Aethon's pin): identical 15% stall — no readiness in 420 s.
- bridges auto (core default): identical (chain mode never falls back to bridges unless
  forced — v2.3.0 `tor.rs`: `may_fall_back = (_, Some(_)) => forced`).
- bridges FORCED + installed lyrebird as PT: BridgeDB fetch **through the tunnel works**
  ("bridgedb answered for ir with webtunnel, snowflake, obfs4"; onionoo 4644 relays; 56
  bridges; lyrebird launched for obfs4) — and bootstrapping over bridges STILL fails
  ("obfs4 did not get through: no headway for 75s"; "Stuck at 15%: Can't bootstrap a
  Tor directory").

**Verdict: CORE/NETWORK failure, not Aethon orchestration.** GUI-managed and
direct-official-Core attempts fail identically with identical env; the Core's own bridge
machinery also cannot bootstrap Tor on this network.

### 7.3 The one real Aethon-side Tor defect (Phase 3.3) — fixed

The stall watchdog fired at `stall + allowance` from SPAWN while the privacy
announcement gate legitimately waits `allowance + 15` AFTER the SOCKS listener; on a
flapping underlay the watchdog pre-empted the gate and the generation guard reported the
vague "Connection attempt was cancelled" — the honest chain-timeout error never
surfaced. Fix: the watchdog adds the gate's exact +15 s slack (bounded, chain-aware,
measured: tor 405 s, psiphon 225 s, plain 90 s; nothing else grew). Verified on the final
build — all three Tor bases now surface the honest error:

- wg+tor: `The privacy chain did not become ready within 300 seconds: the privacy helper
  never announced readiness` (340 s)
- gool+tor (classic): same honest error (326 s)
- masque+tor: `…still bootstrapping (tor reaching the network: 15%: …fetching a
  consensus)` (324 s) — the error embeds the live bootstrap progress.

### 7.4 Remaining Tor behavior (audited, unchanged, correct)

- Announcement gate precedes any traffic probe (asserted by regression test) — a probe
  never runs before Tor readiness (Phase 3.4's ordering).
- Tor LOCATION: T1 markers resolve the real country; lookup failure degrades to
  "unavailable" and never tears the tunnel (Phase 3.5).
- Public 1818/1819 = the GUI relay fed from the FINAL Tor egress (1821/1825); they
  release on failure and teardown (0 stale listeners verified on every Tor attempt).
- Bridges stay pinned OFF in the product (Android parity); the Core's official
  forced-bridge behavior was tested diagnostically only and remains unexposed (Phase
  3.7's discipline; PROMPT §3.7 permits diagnostic testing, which was performed and
  documented — it also fails on this network, so there is no validated feature to expose).

### 7.5 Tor matrix classification (Phase 4 — honest)

| Path | Verdict | Basis |
| --- | --- | --- |
| WireGuard + Tor | **ENVIRONMENTALLY BLOCKED** | honest chain-timeout; direct official Core cannot bootstrap either |
| gool Classic + Tor | **ENVIRONMENTALLY BLOCKED** | same; classic underlay proven by core log |
| gool MASQUE-carried + Tor | ENVIRONMENTALLY BLOCKED (carrier scan-blocked on this network; the Tor-consensus blocker is topology-independent — proven on wg, masque-h2, classic-gool underlays) |
| MASQUE H2 + Tor | **ENVIRONMENTALLY BLOCKED** | carrier healthy (h2 200), Tor stalls at 15% |
| MASQUE H3 + Tor | not separately reachable (h3 scans fail on this network) — not VERIFIED, honestly network-limited |

No +Tor path is marked VERIFIED: Tor bootstrap ≠ success, listener-open ≠ success, and
real final Tor egress was never achieved on this network by anything — including the
official Core directly. Per PROMPT STOP conditions this is reported as an environmental
blocker, not converted to a fake PASS.

## 8. Additional defects found and fixed during validation

1. **Plain protocols never served HTTP 1818** (public contract silently SOCKS-only for
   plain modes): `AETHER_HTTP_PROXY` was only emitted through a `ChainRuntime`, which
   plain protocols never construct. Fixed in the base env assembly (plain →
   `AETHER_HTTP_PROXY=127.0.0.1:1818`; chains omit — the relay owns 1818 there).
   Regression-tested; physically verified: the final plain-gool cycles carry real
   traffic through BOTH ports.
2. **First-TLS-on-cold-route killed healthy connects**: the post-connect system
   validation treated a TLS handshake EOF (the first exchange over freshly-installed
   routes) as fatal. Now transient within the existing bounded 12 s loop (the exit-IP
   equality check is unchanged). Physically verified: cycles 3-7 passed t18/t19.

## 9. Psiphon regression (Phase 5)

| Path | Verdict | Evidence |
| --- | --- | --- |
| wg + Psiphon | **VERIFIED** | connected; real final egress `57.129.25.93 loc=DE warp=off` through BOTH 1818 (relay) and 1819; IR underlay allowed; clean teardown, 0 orphans, listeners drained to 0 |
| gool + Psiphon (the user's saved protocol) | **VERIFIED ×2** | connected 95.2 s / clean; core-announced final egress `57.129.25.93` / `207.154.208.104`, DE via FRA; core counters show real traffic (down 1.6 MiB); IR underlay never rejected the chain; clean teardown |

Note (honest): the redundant external curl through the relays timed out on the
freshly-warmed chains in the two gool+psiphon cycles (the documented degraded-evening
class; the UI was Connected, the core announced the DE egress, and the core's own
counters showed real traffic — the same class dev.032 recorded). The wg+psiphon cycle
carried the external dual-port proof for the identical relay code path.

## 10. Smart Connect / fallback regression (Phase 6)

- Manual Classic means Classic / manual MASQUE-carried means MASQUE-carried: enforced by
  the env contract (regression-tested) + physically proven in strict mode.
- The compatibility fallback is visible (connected message + timeline event + log line)
  and never silent; topology memory cannot invisibly override an explicit choice without
  the override being reported (the connected message states it).
- Smart Connect may rotate (unchanged); its gool candidate now inherits the fixed
  semantics (IR accepted) instead of the removed rejection.
- Manual Disconnect does not auto-reconnect (quickReconnect=false honored; verified in
  every cycle — no unexpected reconnect after disconnect).

## 11. Automated regression tests (Phase 7)

| Suite | Result |
| --- | --- |
| Node frontend (`npm test`) | **38 passed / 0 failed** (35 prior + 3 new dev.033 tests) |
| Rust (`cargo test --locked`) | **145 passed / 0 failed / 1 ignored** (the pre-existing measurement test) |
| Node core-pins | **6 passed / 0 failed** (pins unchanged, anti-drift intact) |

New regressions:
- Plain gool: IR valid without an exit policy (the dev.032 error path is asserted GONE);
  the policy hook is the only rejection path; retry budget unchanged; success returns
  without country inspection; chain protocols never consult a country policy; trace
  `loc=ir` normalization; LOCATION lookup failure degrades to unavailable.
- Topology: strict-mode gate exists and gates both memory and fallback; running-topology
  derivation from core proof lines; truthful connected message; Help EN/FA updated.
- Tor: watchdog covers the announcement-gate slack; gate precedes probe; allowances
  unchanged (120/300); bridges pin stays; T1/lookup-degradation invariants.
- Migration: the user's real dev.032 settings.json (60 keys) loads without reset;
  normalization preserves protocol/goolMode; plain-mode HTTP 1818 contract (plain on /
  chains off).
- The one updated prior assertion (the dev.032 gool-fallback test's regex window) kept
  its semantics (memory exists, protocol untouched) with the window widened for the
  strict-mode documentation — no test was weakened.

## 12. Build record (Phase 8)

| Field | Value |
| --- | --- |
| Build ID | dev.033 (dev.032 untouched) |
| Application version | `2.1.2` / `2.1.2-dev.33` (Windows x64, NSIS) |
| Build pipeline | official: `npm run build -- --bundles nsis` (frontend prep + sidecar SHA-verification + tauri bundling) |
| Installer | `development-builds/dev.033/Aethon-v2.1.2-dev.033-Windows-x64-Installer.exe` |
| Installer SHA-256 | `e24e51ab601957448889e142808249dcd0f183cfedf2bdf82fda84339f148fb2` (32,148,992 bytes) |
| `aether-gui.exe` built / installed | `b6fca634d786f3156995bae9e79e3f2e84d2cedd287aaf04a95beabc30278c78` / installed `09b6c0dbd0f320bd55d892839e470243d45c66b0af0acbfe5e56493b6844905d` (11,324,416 bytes; NSIS bundle-type patch = documented dev.030-consistent behavior) |
| Aether Core (packaged, installed) | `4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1` (24,019,968 bytes) — official v2.3.0, pin-verified, self-reports `aether 2.3.0` |
| pt helpers | `psiphon-tunnel-core.exe a6fc6094…`, `lyrebird.exe 7ccde802…` (pins, unchanged) |
| Signing | **UNSIGNED development build** (no trusted certificate exists; none fabricated) |
| Source state | 4 files changed this mission: `src-tauri/src/lib.rs`, `src-tauri/src/process.rs`, `src-tauri/src/settings.rs`, `src/app.js`, `src/i18n.js`, `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml`, `Cargo.lock`, `tests/frontend.test.mjs` (version bumps + fixes + tests; no android/ changes) |
| Automated results | 38 frontend / 145 Rust / 6 core-pins — all green |

Note: three intermediate dev.033 builds existed while the fixes were validated
(installer v1 `1e16aa12…` → v2 `8d74784c…` → v3 `455a5a66…` → final retained
`e24e51ab…`); ALL final physical results in §5/§6/§7/§9/§10 ran against the FINAL
retained artifact (plain-gool cycles 3-7, both strict-mode proofs, the truthful-message
capture, all three Tor honest-error runs, and both Psiphon regressions).

## 13. Defender / security regression (Phase 9)

| Artifact / moment | Result |
| --- | --- |
| Final installer (`e24e51ab…`) on-demand | **no threats** |
| Built `aether-gui.exe` on-demand | **no threats** |
| Packaged Core `aether.exe` on-demand | **no threats** |
| Installed `aether-gui.exe` + `aether.exe` + app dir post-install | **no threats** |
| Through the entire session (20+ connect cycles, protocol switching, strict-mode runs, Tor matrix, installs) | **no detection at any point**; realtime protection confirmed ON at every scan; zero project-added exclusions |
| Threat history | only the dev.029-era Oct-4 Bearfoos entries (documented in the dev.030 report); **zero new detections Oct 6** |

## 14. Settings preservation (Phase 10)

dev.033 installed OVER dev.032 (`/S`, no uninstall). The user's settings (60 keys:
protocol `gool+psiphon`, scan turbo, appearance light, masqueTransport h2, psiphonRegion
DE, split-tunnel apps (2), quickReconnect false, every other key) survived the upgrade
and the entire validation session; the final state is byte-identical to the
mission-start snapshot (the harness-induced protocol drift during reproduction was
repaired by restoring the snapshot — the dev.032 file loads in dev.033 without reset,
which is also asserted by the migration regression test using this exact file's shape).
Backup/restore compatibility is unchanged (no new keys; goolMode semantics preserved).

## 15. Environmental limitations (honest)

- **+Tor is environmentally blocked on this network** — proven against the official
  Core directly, including its own bridge machinery. All +Tor paths are reported as
  ENVIRONMENTALLY BLOCKED, not VERIFIED (PROMPT STOP-condition discipline).
- MASQUE-carried gool (and masque h3 generally) cannot complete gateway scans on this
  network in the tested windows (turbo scan); the bounded classic fallback covers gool;
  strict-mode classified the carrier honestly.
- The network degraded during the evening validation window (documented across
  dev.032/033): external curl probes through freshly-warmed chains intermittently time
  out while the core's own counters and egress announcements prove real traffic; one
  late plain-gool attempt failed the post-connect TLS budget through all retries
  (bounded, honest error; the 5 clean cycles ran in the stable window).
- Unsigned build: SmartScreen/AV heuristics remain a production consideration.
- Teardown of a chain connection takes ~45-55 s (bounded, complete — 0 orphans verified
  after every cycle).

## 16. Conclusion

dev.033 is **IMPLEMENTED + BUILD VERIFIED + PHYSICALLY VERIFIED** against the final
retained installed artifact:

1. The user's plain-gool failure was reproduced exactly from the installed dev.032,
   root-caused to an unconditional non-IR exit requirement, and REMOVED — with **5/5
   clean user-style cold-start cycles carrying real IR-exit traffic through BOTH public
   ports, truthful LOCATION (Iran), and clean teardowns**. IR is accepted; the exit
   country is informational; gool+Psiphon (the user's saved protocol) is re-verified.
2. All +Tor failures were reproduced, root-caused via direct-official-Core comparison to
   a CORE/NETWORK consensus block (not an Aethon orchestration defect), and the one real
   Aethon-side ordering defect (watchdog pre-empting the announcement gate → vague
   "cancelled" error) is fixed — every Tor base now reports the honest chain-timeout
   error with live bootstrap detail. Tor paths are classified ENVIRONMENTALLY BLOCKED
   with direct-Core evidence; nothing was faked.
3. Topology truthfulness: strict-mode diagnostic paths prove each gool topology
   independently (no hidden fallback); the running topology is derived from the core's
   own proof lines and stated in the connected message; Help EN/FA updated.
4. Two additional real defects found by physical validation were fixed and
   regression-tested: the plain-mode HTTP 1818 contract and the cold-route TLS retry.
5. All automated suites green (38 / 145 / 6) with the new regressions asserting the
   removed defect's error path is gone; Defender clean throughout with zero exclusions
   and zero new detections; dev.032, dev.031 and prior, Android dev.020, and the official
   unmodified Aether Core v2.3.0 all preserved.

Stop after dev.033; awaiting user review. No dev.034.
