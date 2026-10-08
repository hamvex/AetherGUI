# AETHER v2.3.0 ↔ AETHON FEATURE GAP AUDIT (dev.032)

Date: 2026-10-05
Mission: dev.032, Phase 2 (PROMPT §2)
Source of truth: the EXACT official `v2.3.0` tag source
(`work/windows-dev030-20261004/aether-core-v2.3.0-src/`, tag `v2.3.0` → commit
`6175b67df370ab856bcee07fe85b524031903956`, provenance chain verified in the dev.030
audit and re-verified this mission) and `aether --help` / `aether --version` executed
from the pinned, hash-verified packaged binary (`4834bec4…`, self-reports
`aether 2.3.0`).
Aethon baseline: dev.031 source state before this mission's changes (reconstructed
verbatim as `work/windows-dev032-20261005/dev031-equivalent/` for the fail-on-old
regression proof).

Method: every official v2.3.0 user-selectable networking capability was read from the
tag source (CLI definitions in `cli.rs`, env parsing at each use site, mode enums in
`psiphon.rs`/`tor.rs`/`prober.rs`/`wg_prober.rs`, protocol dispatch in `lib.rs`) and
compared against Aethon's exposed UI (`src/index.html`), stored settings
(`src-tauri/src/settings.rs`), and runtime env generation (`environment_with_chain`).
No feature was taken from README text alone; each row below cites the source location.

---

## 1. Feature-gap matrix

Classifications: EXPOSED_AND_WORKING / SUPPORTED_BUT_NOT_EXPOSED /
PARTIALLY_INTEGRATED (fixed by dev.032) / UNSAFE_FOR_AETHON_ARCHITECTURE /
NOT_APPLICABLE / BLOCKED_BY_UPSTREAM / UNKNOWN.

| # | v2.3.0 capability | Source evidence | Aethon dev.031 state | Classification | dev.032 action |
| --- | --- | --- | --- | --- | --- |
| 1 | **MASQUE base transport** (`AETHER_PROTOCOL=masque`) | lib.rs:3236 parse map | Full UI entry, H2/H3 selector, env, validation, topology, verified traffic (dev.030 matrix) | EXPOSED_AND_WORKING | none |
| 2 | **WireGuard base** (`wg`) | lib.rs Protocol::WireGuard | Full entry; default chain wg+psiphon | EXPOSED_AND_WORKING | none |
| 3 | **gool over MASQUE** (v2.3.0 default gool; `AETHER_GOOL_MODE` unset) | lib.rs:261 `WarpInWarp if !gool_classic()`; lib.rs:2819 `gool_classic()` | NOT exposed: dev.031 gool had no mode control; the v2.3.0 default silently changed every plain-gool user's topology. Worse: its MASQUE-gateway scan failure was not detected for gool (wait_for_core_socks matched base `masque` only) → the release-blocking connect failure | **PARTIALLY_INTEGRATED → fixed**: goolMode setting + UI selector + env `AETHER_GOOL_MODE` + scan-failure detection + bounded classic fallback + topology memory | implemented this mission |
| 4 | **classic gool / nested WireGuard** (`AETHER_GOOL_MODE=classic`, `--gool-classic`; auto-selected by any `AETHER_WIW_*_PEER`) | lib.rs:2819-2826; cli.rs:559-561 | Reachable only implicitly via custom WiW endpoints; no user-visible choice | **PARTIALLY_INTEGRATED → fixed**: exposed as the gool "Classic (WARP-in-WARP)" option; custom endpoints still auto-select it (upstream behavior preserved); Help explains both | implemented this mission |
| 5 | **MIM (MASQUE-in-MASQUE)** | lib.rs:279 `MasqueInMasque` | Entry + peer fields + H2/H3 | EXPOSED_AND_WORKING (egress physically verified in earlier windows; environmentally blocked in the dev.030 window — documented, not a source defect) | none |
| 6 | MASQUE HTTP/3 vs HTTP/2 (`AETHER_MASQUE_HTTP2`) | cli.rs:381 | Segment control + h3→h2 fallback in connect flow | EXPOSED_AND_WORKING | none |
| 7 | **gool custom inner peer** (`AETHER_GOOL_INNER` / `--gool-peer`) | lib.rs:2833 `gool_inner_peers()` | Not exposed (separate from WiW endpoints; names the WG hop the MASQUE-carried gool dials inside the tunnel) | SUPPORTED_BUT_NOT_EXPOSED — deliberately: the WiW endpoint fields cover the custom-endpoint use case, and this knob is an escape hatch only meaningful with knowledge of specific gateway internals; exposing two near-identical peer fields would invite misconfiguration. Documented here per §2.2 honesty requirements | documented (no fake UI) |
| 8 | **scan modes: turbo/balanced/thorough/verified/ironclad** (v2.3.0 renamed `stealth`→`verified`; old value still parses as alias) | prober.rs:160 `"verified"\|"proven"\|"stealth"\|"quiet"`; wg_prober.rs:35 | All five in the dropdown but the `stealth` option still labeled "Stealth"/"پنهان" — an obsolete label carried from v2.1.0 | **PARTIALLY_INTEGRATED → fixed**: label now "Verified"/"تأییدشده" (EN+FA); stored value stays `stealth` for migration compatibility (core parses it as alias); `scan_mode_label()` pins the mapping in Rust | implemented this mission |
| 9 | quick reconnect / lastconn cache (`AETHER_QUICK_RECONNECT`) | lib.rs:189-248 lastconn | Toggle + endpoint pin cache + bounded invalidation (rescan on pin failure) | EXPOSED_AND_WORKING | none |
| 10 | **Psiphon topology `chain`** (aether → tunnel → psiphon → net) | psiphon.rs Mode::Chain, run_chain | The +Psiphon protocols; announcement gate; public relay contract | EXPOSED_AND_WORKING | none |
| 11 | **Psiphon topology `reverse`** (psiphon → tunnel → net; `AETHER_PSIPHON=reverse`) | lib.rs:214-225: sets `AETHER_UPSTREAM` to psiphon's SOCKS and forces MASQUE H2; REFUSES wg/gool (TCP-only carrier cannot reach UDP WG endpoints) | Not exposed | UNSAFE_FOR_AETHON_ARCHITECTURE — exact reason: (a) it only runs over the MASQUE/H2 carrier, so the "WireGuard + Psiphon" default and both gool topologies are refused by the core itself; (b) the core moves the tunnel's dial-out onto the psiphon SOCKS (1822) and binds the final SOCKS on the public `--bind` (1819) directly, which collides with Aethon's chain-mode architecture where the GUI owns 1818/1819 as relays to the chain egress — the relay contract, announcement gating, exit-IP validation, and split-tunnel routing would all point at the wrong hop; (c) final egress is a WARP exit whose IP the core reports via `psiphon is ready; the tunnel goes out through {socks}`, an announcement Aethon's gate does not consume | NOT EXPOSED (documented; no fake switch) |
| 12 | **Psiphon topology `only`** (no tunnel; `AETHER_PSIPHON=only`) | lib.rs:127 `run_only`; psiphon.rs run_only binds 1819 as plain psiphon | Not exposed | UNSAFE_FOR_AETHON_ARCHITECTURE — exact reason: with mode `only` the core serves plain Psiphon SOCKS5 directly on the public port and no WARP tunnel exists; Aethon's whole Device-VPN architecture (TUN adapter, xray routing, DNS handling, split tunneling, kill-switch, data-plane validation) wraps a WARP-family tunnel. Psiphon-only would also bypass Aethon's 1818/1819 relay ownership in a topology the relays were never designed for, and the Psiphon "app alone" use case is exactly what Aethon is not (it is a device VPN, not a standalone Psiphon client) | NOT EXPOSED (documented) |
| 13 | **Tor topology `chain`** | tor.rs run_chain; Aethon pins `AETHER_TOR=chain`, bridges off | The +Tor protocols | EXPOSED_AND_WORKING (environmentally blocked at Tor consensus on this host — documented across dev.027/028/030) | none |
| 14 | **Tor topology `reverse`** (`AETHER_TOR=reverse`) | lib.rs:186-199: same MASQUE-H2-only + upstream remap + wg/gool refusal as psiphon reverse | Not exposed | UNSAFE_FOR_AETHON_ARCHITECTURE — same three reasons as #11 (carrier restriction, upstream/port contract collision, announcement the gate does not parse) | NOT EXPOSED (documented) |
| 15 | **Tor topology `only`** (`AETHER_TOR=only`) | lib.rs:123 run_only | Not exposed | UNSAFE_FOR_AETHON_ARCHITECTURE — same reason as #12 plus the core's Tor-Only mode answers UDP ASSOCIATE for DNS on the SOCKS listener, a listener Aethon's relays pipe verbatim but whose egress semantics (Tor exit) differ from every validated topology | NOT EXPOSED (documented) |
| 16 | Tor bridges: fetched through tunnel, bridge files, PT binaries, onionoo relays (`--tor-bridges`, `--tor-bridge`, `--tor-bridge-file`, `--tor-relays`, `--tor-pt`, `--tor-pt-dir`) | bridges.rs; tor.rs | Aethon pins `AETHER_TOR_BRIDGES=off` (Android dev.020 parity) | NOT_APPLICABLE for the product default: Aethon's +Tor design is the chain topology through the WARP underlay with bridges off; the bridge machinery is an upstream escape hatch for Tor-carrying networks. Re-evaluated per §2.3: exposing bridge config would add a surface (file paths, PT binaries) that the product has never validated and that the environment (this host cannot even reach Tor consensus) cannot test — a dead/fake control is worse than none | NOT EXPOSED (documented) |
| 17 | Psiphon server-entries file, custom psiphon config, CDN fronting overrides (`--psiphon-config`, `--psiphon-server-entries`, `--psiphon-cdn-*`) | psiphon.rs | Not exposed | NOT_APPLICABLE: `cdn` transport is blocked by product decision (fronted meek 400/404 root cause, Android dev.017-020, reproduced on Windows); the core's built-in credentials work on fresh machines (v2.3.0 release note), so custom configs solve a problem Aethon users do not have; exposing raw JSON-config/file-path controls would widen the validation surface for zero user benefit | NOT EXPOSED (documented) |
| 18 | Obfuscation profiles off/light/firewall/balanced/gfw/aggressive (`AETHER_NOIZE`) | noize.rs; cli.rs | Aethon exposes firewall/gfw/balanced/aggressive/off (light omitted deliberately — documented in dev.031-era analysis as near-identical to off) | EXPOSED_AND_WORKING | none |
| 19 | ECH auto/base64 + `--ech-dns`/`--ech-domain` (v2.3.0 adds DoH resolver + domain selection) | tls.rs; cli.rs | Aethon exposes ECH Off/Auto/Custom(base64). The new `AETHER_ECH_DNS`/`AETHER_ECH_DOMAIN` are not exposed | SUPPORTED_BUT_NOT_EXPOSED — deliberately: v2.3.0's defaults (`udp://1.1.1.1`, `cloudflare-ech.com`) are the correct values for the WARP/MASQUE endpoints Aethon uses; the additions exist for non-Cloudflare MASQUE deployments Aethon does not support. Exposing a DoH URL field adds an injection-adjacent env surface for no product benefit | documented |
| 20 | TLS fingerprint controls (`--tls-ciphers`, `--tls-groups`, `--disable-grease`) | cli.rs | Not exposed | NOT_APPLICABLE: v2.3.0 defaults replicate Chrome's BoringSSL fingerprint — the anti-DPI property Aethon wants. User-tuning it degrades fingerprint mimicry; a power-user can set `AETHER_TLS_CIPHERS` etc. only by hand-editing env, which Aethon's process spawn deliberately strips (stale AETHER_* flags are filtered) | NOT EXPOSED (documented) |
| 21 | `--register` (provision identities, no tunnel) | lib.rs:1016+ | Not exposed | NOT_APPLICABLE: Aethon's connect flow provisions identities automatically on first use (observed in core logs); a separate register control adds nothing for a GUI user | documented |
| 22 | `--exit-loc` country accept/refuse lists (`AETHER_EXIT_LOC`) | exitloc.rs | Not exposed | SUPPORTED_BUT_NOT_EXPOSED — deliberately: Aethon implements the same guarantee at the product level for the chain protocols (Psiphon region selector; the exit-retry loop rejects an IR exit for gool with bounded attempts), while the core's own continuous re-check would fight Aethon's validation/watchdog contract. Revisit only if users ask for exit-country pinning of plain protocols | documented |
| 23 | `--mark` / firewall mark (Linux/Android) | cli.rs | Not exposed | NOT_APPLICABLE on Windows (SO_MARK does not exist; upstream gates it to Linux/Android) | none |
| 24 | Stats reporter (`AETHER_STATS`, `AETHER_STATS_SECS`) | stats.rs | Set by Aethon; parse + display; binary units; v2.3.0 day-prefixed uptime ignored by parser | EXPOSED_AND_WORKING | none |
| 25 | `--perf low/medium/high` (`AETHER_PERF_PROFILE`) | sysprofile.rs | Not exposed | NOT_APPLICABLE: v2.3.0 auto-detects (log: `performance profile: Medium`); the machine-specific override is a server/router knob | documented |
| 26 | `--enroll-address`, `--api-fragment` | account.rs / api.rs | Not exposed | NOT_APPLICABLE: WARP-API reachability workarounds for networks that filter the API domain; the scan modes + profiles already own this class of problem in Aethon's validated flows | documented |
| 27 | IPv4/IPv6/both scanning (`AETHER_IP`) | prober.rs | Exposed (v4/v6/both) | EXPOSED_AND_WORKING | none |
| 28 | Routing block/direct/rules-file + route sniffing | routing.rs (core) | Exposed (block/direct lists, routes file, sniff toggle + ms) | EXPOSED_AND_WORKING | none |
| 29 | Upstream proxy for the core (`AETHER_UPSTREAM`) | upstream.rs | Exposed, with the Psiphon/Tor conflict guards (Android parity) | EXPOSED_AND_WORKING | none |
| 30 | **MASQUE H2 fragmentation** (`AETHER_MASQUE_H2_FRAGMENT*`) | fragment.rs:33-49 (+ new optional `_SNI` sibling) | Exposed (toggle + size + delay). `_SNI` variant not exposed | EXPOSED_AND_WORKING; `_SNI` NOT_APPLICABLE (upstream default keeps SNI behavior correct for the WARP endpoints; the fragment already defeats the ClientHello reset) | none |

---

## 2. What dev.032 actually integrates (end-to-end mapping)

| Item | UI | Stored setting | Validation | Runtime env | Topology/egress effect |
| --- | --- | --- | --- | --- | --- |
| gool topology: MASQUE-carried (v2.3.0 default) | "MASQUE-carried" segment in Configurations → Protocol block (gool family only) | `goolMode: "masque"` (default; old files deserialize to it) | `GoolMode::parse` normalization (unknown → masque) | nothing set (core default is exactly this) | WG tunnel carried inside MASQUE; foreign exit (core log: `gool: masque device=… carries the wireguard identity`) |
| gool topology: Classic | "Classic (WARP-in-WARP)" segment | `goolMode: "classic"` | normalization accepts classic/wiw/wg spellings | `AETHER_GOOL_MODE=classic` | WG-in-WG; plain WARP exit (core log: `outer device=… \| inner device=…`) |
| Scan label rename | "Verified"/"تأییدشده" option label | stored value remains `"stealth"` (core alias, zero migration risk) | existing scan-mode validation untouched | `AETHER_SCAN=stealth` → core parses as Verified | verified-only dialing (prober strategy table) |
| Reliability fallback | no new control (internal, same Connect click) | — (memory file `gool-mode.txt`, advisory) | bounded: one fallback attempt, terminal error if both fail | first attempt: user's mode; on MASQUE-scan failure retry: `AETHER_GOOL_MODE=classic` | the user's saved `gool+psiphon` connects again on networks that block MASQUE scans |

Preservation guarantees (verified by tests): the stored `protocol` is never modified by
the fallback or the topology memory; custom WiW endpoints keep forcing classic in-core
(upstream behavior); Reset Defaults yields `goolMode: "masque"` (official default);
backup/restore round-trips the new field (serde round-trip test); old settings.json
without the key loads with the v2.3.0 default.

---

## 3. Protocol selector inventory — before / after

dev.031 (11 entries, unchanged in dev.032 — no entries added or removed):
smart, masque, wg, gool, mim, masque+psiphon, wg+psiphon, gool+psiphon, masque+tor,
wg+tor, gool+tor.

dev.032 adds no new protocol entries. The v2.3.0 gool capability is a per-protocol
**topology choice**, exposed as the conditional "gool Topology" control (exactly like
the existing "MASQUE Connection Method" pattern) rather than duplicated dropdown
entries — two materially different topologies are never silently called the same name
(PROMPT §2.4), and no dead entries were added (every new control maps end-to-end).

Default protocol: unchanged — **WireGuard + Psiphon** (PROMPT §2.6; no benchmark could
fairly justify a change in this network window, and the reliability fix restores the
user's saved gool+psiphon without touching defaults).

---

## 4. Upstream capabilities NOT integrated, with exact technical reasons

See rows 7, 11, 12, 14, 15, 16, 17, 19, 20, 21, 22, 25, 26 in §1. Summary of the three
architecture-level refusals (all verified against the v2.3.0 tag source, lib.rs
lines 123-229):

1. **`reverse` topologies** (Tor and Psiphon): core restricts them to the MASQUE/H2
   carrier (refuses wg/gool — `warp's wireguard endpoints answer on udp alone`), remaps
   the tunnel dial-out onto the privacy helper's SOCKS via `AETHER_UPSTREAM`, and binds
   the final SOCKS on the public `--bind` port directly. That port/egress contract is
   mutually exclusive with Aethon's chain-mode relay architecture (GUI owns 1818/1819
   and pipes them to the chain egress). Exposing them would produce a fake control that
   either fails at validation or silently bypasses the relay/validation contract.
2. **`only` topologies**: no WARP tunnel at all; the core serves the privacy helper
   directly on the public port. Aethon is a device VPN wrapping a WARP-family tunnel
   (TUN, xray routing, DNS, split tunneling, kill switch all assume the underlay);
   a "Tor alone"/"Psiphon alone" mode is a different product.
3. **Tor bridge machinery**: valid upstream, but Aethon pins bridges off by design
   (Android dev.020 parity) and the physical environment cannot validate bridge
   behavior (Tor consensus unreachable on this host). An unvalidatable switch would be
   exactly the "fake or nonfunctional switch" PROMPT §2.3 forbids.

---

## 5. Verdict

"Binary upgraded to v2.3.0" was NOT equivalent to "v2.3.0 integrated": dev.031 had one
materially changed capability (gool topology split) that was (a) invisible to the user,
(b) mis-detected by the connect flow when its carrier failed — the release-blocking
connection defect — and (c) labeled with a stale scan-mode name. dev.032 integrates the
genuinely integrable surface (gool topology end-to-end + truthful labels + bounded
reliability fallback), documents every deliberate non-integration with exact source
citations, and adds no dead UI entries.
