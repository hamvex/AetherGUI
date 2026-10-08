# AETHON dev.033 — GOOL + TOR ROOT-CAUSE AUDIT

Date: 2026-10-06
Workspace: `D:\project\AetherGUI-v2.1.2`
Baseline: dev.032 (immutable; installer `07ca4e3c…` re-hashed unchanged at mission start)
Core: official unmodified Aether v2.3.0 (pin `4834bec4…`, tag `v2.3.0` → commit
`6175b67df370ab856bcee07fe85b524031903956`, runtime self-ID re-executed, pre-spawn digest
gate intact — `process.rs:31` / `process.rs:929`).

This is the supporting audit for AETHON_WINDOWS_DEV033_FINAL_REPORT.md. Everything below
was reproduced from the physically installed dev.032 artifact with normal user
interaction before any source change, then root-caused against the exact official v2.3.0
tag source, then fixed Aethon-side only.

---

## DEFECT 1 — plain gool rejected a healthy IR exit

### 1.1 Reproduction (user-style, installed dev.032)

Real UI interaction: hamburger drawer → Configurations → Protocol `<select>` (native
dropdown, real click + arrow keys + ENTER) → `gool` → Home → ONE real mouse click on the
Connect orb. CDP used read-only for observation only. Evidence:

- `work/windows-dev033-20261006/repro-plain-gool.json` + `repro-plain-gool-5.out`
- `repro-plain-gool-timeline.log` (the attempt's full pre-xray timeline)
- `repro-plain-gool-core.log` (the attempt's full core log)
- `PHASE0-1-plain-gool-repro-notes.txt`

Result — the EXACT user error, at 29033 ms:

```
connect_failed: "GOOL could not obtain a non-IR exit after 3 bounded attempts:
attempt 1 returned IR; attempt 2 returned IR; attempt 3 returned IR"
```

UI states: Scanning → Connecting → Scanning → Connecting → Reconnecting → Connection error.

### 1.2 Proof the tunnel was healthy before each rejection (attempt 3, core log)

```
07:15:38.837  [+] [outer] wireguard tunnel validated (end-to-end data confirmed)
07:15:39.383  [+] [inner] wireguard tunnel validated (end-to-end data confirmed)
07:15:39.384  [+] socks5 server listening on 127.0.0.1:1819        <- public 1819 bound
07:15:39.868  [+] warp-in-warp exit: 104.28.246.167, IR via FRA, 484ms, warp on
```

- The Core established the tunnel (both hops handshake-validated, data-plane confirmed).
- The public 1819 listener was bound and functional.
- The exit IP was real and public (104.28.246.167), country IR (Cloudflare WARP exits
  from this network are consistently IR — 20+ observations across the session logs).
- The Aethon trace probe completed a REAL end-to-end HTTPS exchange through the tunnel
  (probe success is a precondition of the country check; the SOCKS handshake + TLS +
  HTTP + exit-IP parse all succeeded).
- Aethon then killed this working connection SOLELY because `probe.country == "IR"`,
  invalidated the endpoint cache, restarted, and repeated — three times.

### 1.3 Root cause (source, dev.032)

`src-tauri/src/lib.rs` `start_validated_protocol`:

```rust
if settings.protocol != "gool" || probe.country != "IR" {
    … return Ok((generation, probe));      // accepted
}
failures.push(format!("attempt {exit_attempt} returned IR"));
invalidate_endpoint_cache(…);
let _ = process.stop().await;               // kill the healthy tunnel
… // retry; after 3 attempts:
Err("GOOL could not obtain a non-IR exit after {max_exit_attempts} bounded attempts: …")
```

An unconditional product-level non-IR requirement for protocol `gool` — exactly the
class PROMPT §1.1 enumerates ("reject IR exit / require non-IR / retry until non-IR /
fail after N IR exits / treat Iran as an invalid gool result"). Reachable from plain
gool AND Smart Connect's `gool` candidate (smart feeds candidate protocol "gool" into
the same function). Chain entries (`gool+psiphon`, `gool+tor`) never hit it — which is
why gool+Psiphon worked for the user while plain gool did not.

### 1.4 Why the exit is IR (informational, not a defect)

Cloudflare WARP assigns exits by network geography; from this network the WARP-family
exit (wg single-hop, warp-in-warp classic gool, masque) is consistently an Iranian
address (`104.28.214.x/246.x …, IR via FRA/ARN/AMS/GYD, warp on`). That is a LOCATION
fact, not a connectivity fact: every probe through it completed real TLS end-to-end.

### 1.5 Fix (dev.033)

- Removed the IR-rejection branch entirely. Success is defined by the data plane
  (listener + real HTTPS trace + public exit IP); the country is never inspected on the
  plain-gool path.
- Country-based rejection now exists ONLY behind `exit_country_policy_rejected`
  (lib.rs), which consults an explicit user-configured exit-country policy; none exists
  in the product's settings (the only country setting, `psiphon_region`, is the Psiphon
  chain's own egress request handled by the core), so every country — IR included — is
  accepted. The hook exists so a future real exit policy has exactly one place to live.
- Retry budget unchanged (3/2) — NOT "fixed" by adding retries (PROMPT §1.1).
- LOCATION display is unchanged-truthful: the trace `loc=ir` renders as Iran/Tehran via
  the same providers; provider failure renders "unavailable" and never tears the tunnel.

### 1.6 Physical verification (final dev.033 artifact, user-style cold-start cycles)

5/5 clean cycles (installer `e24e51ab…`):

| Cycle | Connect | Exit (1819 SOCKS + 1818 HTTP, real TLS) | LOCATION | Teardown |
| --- | --- | --- | --- | --- |
| 3 | 58.5 s | 104.28.246.163 loc=IR warp=on (BOTH ports) | Iran | clean, 0 orphans/listeners |
| 4 | 85.1 s | 104.28.214.167 loc=IR warp=on (BOTH ports) | Iran | clean |
| 5 | 50.5 s | 104.28.214.168 loc=IR warp=on (BOTH ports) | Location unavailable (honest provider failure) | clean |
| 6 | 43.5 s | 104.28.214.167 loc=IR warp=on (BOTH ports) | Iran | clean |
| 7 | 40.2 s | 104.28.214.167 loc=IR warp=on (BOTH ports) | Location unavailable | clean |

Every cycle: app closed initially, zero owned processes/listeners, launch, select `gool`
via the real UI, ONE Connect click, Connected, real traffic through BOTH public ports,
truthful LOCATION, disconnect, zero stale processes/listeners.

---

## DEFECT 2 — all +Tor combinations fail

### 2.1 Reproduction (user-style, installed dev.032)

Three bases, real UI protocol selection, ONE real Connect click each:

| Base | Evidence | Result |
| --- | --- | --- |
| wg+tor | `PHASE0-2-wg-tor-repro-notes.txt`, `repro-wg-tor-core.log`, `repro-wg-tor-timeline.log` | FAILED ~54 min of core flap-retry cycles; final toast "Connection attempt was cancelled" |
| gool+tor (classic underlay) | `PHASE0-2-tor-repro-notes.txt`, `repro-gool-tor-core.log`, `repro-gool-tor-timeline.log` | FAILED at 329598 ms: "The privacy chain did not become ready within 300 seconds: the privacy helper never announced readiness" |
| masque+tor | `PHASE0-2-masque-tor-repro-notes.txt`, `repro-masque-tor-core.log`, `repro-masque-tor-timeline.log` | FAILED at 319397 ms: "…still bootstrapping (tor reaching the network: 15%: connecting successfully; directory is fetching a consensus)" |

The user's own attempts (their log, 05:43Z wg+tor and 05:48Z gool+tor) show the same
signature — Tor reaches 15% and never progresses.

### 2.2 The universal failure signature

Every base: the underlay comes up healthy (wg/masque/classic-gool all validated
end-to-end; SOCKS 18193 bound), Tor starts, bootstraps 0% → 8% → **15%** in ~1 second,
and then the consensus fetch makes NO progress for the entire budget. No "tor is ready"
line is ever printed. Bridges are pinned off in the Aethon chain env
(`AETHER_TOR_BRIDGES=off`, Android parity).

### 2.3 Direct-Core comparison (Phase 3.2 — the decisive test)

The exact official packaged Core (`aether.exe`, pin-verified), run OUTSIDE the GUI with
Aethon's exact chain environment (AETHER_SOCKS=18193, AETHER_TOR=chain, TOR_BIND=1821,
TOR_HTTP=1825, TOR_DIR, AETHER_PROTOCOL per base, MASQUE_HTTP2=1, SCAN=turbo, identity
dir = the real user data dir):

| Test | Env | Result |
| --- | --- | --- |
| A | bridges OFF (Aethon's pin) | base carrier healthy; Tor 0→8→15% stall for 420 s; no readiness |
| B | bridges auto (core default; chain mode does NOT fall back unless forced — v2.3.0 `tor.rs` `may_fall_back = (_, Some(_)) => forced`) | identical 15% stall, forever |
| C | bridges FORCED (`AETHER_TOR_BRIDGES=auto`) + `AETHER_TOR_PT_DIR` → installed lyrebird | BridgeDB fetch THROUGH the tunnel works ("bridgedb answered for ir with webtunnel, snowflake, obfs4" + onionoo 4644 relays → 56 bridges; lyrebird launched for obfs4); bootstrapping over bridges still fails: "obfs4 did not get through: no headway for 75s", "tor over bridges: Stuck at 15%: Can't bootstrap a Tor directory. (Can't make progress.)" |

Evidence: `direct-core-bridges-off.log`, `direct-core-bridges-auto.log`,
`direct-core-forced-bridges.log`, `PHASE3-2-direct-core-tor-notes.txt`.

**Conclusion: the +Tor failure is CORE/NETWORK, not Aethon orchestration.** The
GUI-managed and direct official-Core attempts fail identically with identical
environments; even the Core's own bridge machinery (BridgeDB + onionoo + lyrebird PT)
cannot complete bootstrap on this network. Tor directory traffic through the WARP-family
underlay (and PT-dial-direct, which bypasses the underlay by upstream design) is blocked.

### 2.4 The one real Aethon-side defect found in the Tor path (ordering)

dev.032's stall watchdog fired at `stall_timeout + allowance` measured from SPAWN, but
the privacy announcement gate legitimately waits `allowance + 15` AFTER the SOCKS
listener — on a flapping underlay (measured: wg+tor reached SOCKS at ~75 s) the gate's
deadline (75+315=390 s) coincided/exceeded the watchdog's (390 s), so the watchdog
pre-empted the gate and the generation guard converted the kill into the vague
"Connection attempt was cancelled" — the honest "The privacy chain did not become ready
within 300 seconds" error never surfaced. Physically reproduced (the wg+tor 54-minute
failure).

Fix (bounded, chain-aware, measured — not a blanket increase): the watchdog adds the
gate's exact +15 s slack (tor: 90+300+15=405 s; psiphon: 90+120+15=225 s). Verified on
the final build: wg+tor, gool+tor, masque+tor now all surface the honest chain-timeout
error (340 s / 326 s / 324 s) instead of "cancelled".

### 2.5 Tor classification (honest, per PROMPT §4/STOP conditions)

| Path | Verdict |
| --- | --- |
| WireGuard + Tor | **ENVIRONMENTALLY BLOCKED** — honest chain-timeout error; direct official Core also cannot bootstrap |
| gool Classic + Tor | **ENVIRONMENTALLY BLOCKED** — same; underlay verified classic (core log) |
| gool MASQUE-carried + Tor | not separately connectable on this network (MASQUE scan blocked); the chain env cell is covered by classic-gool evidence — the blocker is the Tor consensus, not the gool topology |
| MASQUE H2 + Tor | **ENVIRONMENTALLY BLOCKED** — carrier healthy (h2 200), Tor stalls |
| MASQUE H3 + Tor | not testable separately on this network (masque h3 scans fail here; h2 fallback owns the carrier) — classified honestly as network-limited, not VERIFIED |

No Tor path is marked VERIFIED: Tor startup/bootstrap is never treated as success
(PROMPT's explicit instruction); real final Tor egress was never achieved on this
network, by the GUI OR by the official Core directly.

---

## DEFECT 3 (found during validation) — plain protocols never served HTTP 1818

Physical evidence: a connected plain-gool cycle served real traffic on SOCKS 1819 while
HTTP 1818 refused every request. Root cause: `AETHER_HTTP_PROXY` was only ever inserted
by `ChainRuntime::environment()`'s `None` arm — but plain protocols never construct a
ChainRuntime (`resolve_chain_runtime` returns `None` for plain protocols), so the core
never received the HTTP listener address and never bound 1818. The public dual-port
contract silently degraded to SOCKS-only for all plain protocols.

Fix: the base env assembly (`settings.rs`) pins `AETHER_HTTP_PROXY=127.0.0.1:1818` for
plain protocols; chain protocols omit the key (the GUI relay owns 1818 there, as the
existing chain-env tests assert). Regression test added
(`plain_protocols_pin_the_public_http_contract_on_the_core`). Physically verified: the
final cycles carry real traffic through BOTH 1818 and 1819 on plain gool.

---

## DEFECT 4 (found during validation) — first-TLS-on-cold-route killed healthy connects

A plain-gool connect that passed every gate (SOCKS ready, probe accepted, Connected
published) was torn down 2 s later by the post-connect system validation with
"tls: TLS handshake failed: tls handshake eof" — the FIRST TLS exchange over the
freshly-installed system routes. The error was marked non-transient, so the bounded 12 s
retry loop never re-attempted; the identical cold-chain class dev.032 fixed for the
SOCKS probes. Fix: the system-plane TLS handshake failure is transient (the bounded
12 s loop retries; a genuinely dead plane still fails after the budget; the exit-IP
equality check is unchanged). Physically verified: cycles 3-7 passed t18/t19 cleanly.

---

## Topology truthfulness (Phase 2) — verification summary

- UI selection → stored goolMode → env → runtime audited end-to-end.
- Strict mode (`AETHON_GOOL_STRICT=1`): topology memory AND classic fallback disabled —
  physically proven: stored masque ran MASQUE-carried (core log "gool: masque device=…
  carries the wireguard identity") and failed honestly with "gateway scan failed" —
  NO hidden fallback (MASQUE-carried = FAILED/ENVIRONMENTALLY BLOCKED on this network;
  not "fixed" by a silent classic swap).
- Explicit Classic via strict mode: UI-selected classic ran classic (core log
  "outer device=… | inner device=…", "warp-in-warp exit"), real traffic both ports,
  LOCATION Tehran, Iran; clean teardown.
- Normal mode fallback truthfulness: the connected status message now states the
  running topology and calls out the fallback explicitly — physically captured:
  "Aether and System-wide VPN Mode are ready (gool classic — the MASQUE-carried gool
  found no gateway, the compatibility fallback selected classic)". The running topology
  is derived from the core's own proof lines (never the stored setting).
- Help EN/FA updated to describe the visible-fallback semantics.

## Chain semantics (Phase 1.3) — verification summary

- The chain-mode connect probe targets the PUBLIC 1819 (fed by the GUI relay from the
  chain's FINAL egress), so gool+Psiphon's success/LOCATION follow the Psiphon exit
  (DE), never the IR underlay. The IR underlay never invalidated a chain (verified in
  the gool+psiphon cycles: underlay IR, final egress DE, connected).
