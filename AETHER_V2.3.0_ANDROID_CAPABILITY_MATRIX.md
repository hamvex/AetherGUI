# Aether Core v2.3.0 — Android (Aethon dev.034) Capability Matrix

Mission: PROMPT.md dev.034. Core: official unmodified Aether v2.3.0, tag `v2.3.0`,
commit `6175b67df370ab856bcee07fe85b524031903956`. Evidence base: the exact tag source
(`work/windows-dev030-20261004/aether-core-v2.3.0-src`, same commit), the executed
`aether --help` of the official release binary
(`work/android-dev034-20261006/upstream-v2.3.0/aether-v2.3.0-help.txt`), the v2.1.0→v2.3.0
diff, and the Android archives verified against the release SHA256SUMS.

Classifications: **A** fully integrated + UI exposed · **B** fully integrated +
automatic/internal · **C** not applicable to Android · **D** blocked by product
architecture · **E** blocked by upstream/environment · **F** unsafe to expose.
For C/D/E/F the specific evidence is stated. "—" = unchanged from the dev.020 baseline
integration, re-verified against the exact v2.3.0 source.

## Connection

| Flag / env | Default (v2.3.0 source) | Classification | Android preference / mapping |
| --- | --- | --- | --- |
| `--bind` / `AETHER_SOCKS` | 127.0.0.1:1819 | **B** | Internal: the service owns the core bind address per topology plan (chain underlay / public relay contract). Not a user setting in the managed product. |
| `--http-proxy` / `AETHER_HTTP_PROXY` | off | **A** | `httpProxy` (VPN mode; disabled in Proxy mode where the app owns 1818/1819). Context-aware. |
| `--upstream` / `AETHER_UPSTREAM` | unset | **A** | `upstreamProxy`; validated `socks5://`, `socks5h://`, `http://` (Core implements HTTP CONNECT without TLS — verified unchanged in v2.3.0 `upstream.rs`). |
| `--mark` / `AETHER_MARK` | unset | **E** | Requires SO_MARK with root or CAP_NET_ADMIN (`--help`: "Linux and Android, needs root or CAP_NET_ADMIN"). The Aethon core process runs as the app UID under VpnService; it has neither. Android's per-app routing is owned by the platform VPN, not firewall marks. Future: possible only with a rooted helper, which the product excludes. |
| `--exit-loc` / `AETHER_EXIT_LOC` | off | **A (OFF physically verified; enabled policy gated)** | `exitLocationEnabled` / `exitLocationMode` (allow/exclude) / `exitLocationCountries`; maps to `!CC,CC` / `CC,CC` exactly. v2.3.0 semantics verified in source and unit tests; policy OFF physically keeps a real IR WARP exit connected with no hidden blacklist. The enabled user-facing policy remains gated (`Exit-country policy pending runtime validation: exitLocationEnabled`) because this mission did not expose the country controls without a dedicated runtime evidence set. |
| `--exit-loc-secs` / `AETHER_EXIT_LOC_SECS` | 60 (max 86400) | **A** | New `exitLocationSecs` expert field (1–86400, default 60). |
| `--stats` / `AETHER_STATS` | off | **A** | New `statsLogging` expert toggle → `AETHER_STATS=1`. Decision (Phase 15): the Android VpnService UID accounting stays the authoritative telemetry (it measures full-device traffic the user sees); Core stats are exposed as opt-in diagnostic logging, not a second UI pipeline. |
| `--stats-secs` / `AETHER_STATS_SECS` | 60 | **C** | Diagnostic log line interval only; fixed 60 is the sane diagnostic cadence, no user value on Android (the UI telemetry is event-driven, not interval-driven). |
| `--quick-reconnect` / `--no-quick-reconnect` / `AETHER_QUICK_RECONNECT` | on | **A** | `quickReconnect` (—). |
| `-4`/`-6`/`--dual`/`--ip` / `AETHER_IP` | v4 | **A** | `ipMode` (—). |
| `--peer` / `AETHER_PEER` | unset | **A** | `peer` (—; also suppresses the Core's interactive carrier prompt). |
| `--wg-peer` / `AETHER_WG_PEER` | unset | **A** | Equivalent surface: `wiwOuterPeer` (the WiW outer hop) is the product's control for the same outer endpoint; classic-only context. |

## Protocol / topology

| Flag / env | Default | Classification | Android preference / mapping |
| --- | --- | --- | --- |
| `--masque` / `--wg` / `--gool` / `--mim` / `AETHER_PROTOCOL` | masque | **A** | Protocol selector (11 product entries incl. chains); `AETHER_PROTOCOL` always set (no interactive menu). |
| `--gool-classic` / `AETHER_GOOL_MODE` | unset = MASQUE-carried | **A** | **New `goolMode`** (`gool_over_masque` | `classic`): a context-aware Gool Mode control directly below Protocol whenever the base protocol is Gool (including gool+Psiphon / gool+Tor). Classic → `AETHER_GOOL_MODE=classic`. NOTE: naming any WiW endpoint also selects classic in the Core (`gool_classic()`), so the WiW endpoint fields are shown only for Classic. Migration: an existing dev.020 Gool user's legacy semantics are preserved as Classic; fresh installs default to the new MASQUE-carried mode. |
| `--gool-peer` / `AETHER_GOOL_INNER` | registration-named endpoint:2408 | **A** | New `goolInnerPeer` advanced field (Gool over MASQUE context): pins the inner WireGuard hop the Core would otherwise take from registration. Validated endpoint. |
| `--api-fragment` / `AETHER_API_FRAGMENT` | off | **A** | New `apiFragment` toggle (MASQUE/WARP-API context): reach the WARP key API only over the fragmented route, for networks that filter the key domain. |
| `--wiw-outer` / `--wiw-inner` / `AETHER_WIW_*_PEER` | scan both | **A** | `wiwOuterPeer` / `wiwInnerPeer` (Classic Gool context only — setting either forces the classic topology in the Core). |
| `--wiw-peers` / `--wiw-scan` / `AETHER_WIW_PEERS` | — | **C** | Redundant encodings of the outer/inner pair and "scan both": the product exposes the two endpoints directly; empty = scan (exactly `AETHER_WIW_PEERS=auto`). No distinct capability. |
| `--mim-outer` / `--mim-inner` / `AETHER_MIM_*_PEER` | scan/pick | **A** | `mimOuterPeer` / `mimInnerPeer` (MIM context). |
| `--mim-peers` / `--mim-scan` | — | **C** | Redundant forms, same as WiW. |

## Scan / obfuscation

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--scan` (turbo/balanced/thorough/verified/ironclad) / `AETHER_SCAN` | balanced | **A** | Scan Mode dropdown (stored values incl. legacy `stealth` alias, normalized). `--verified`/`--stealth` alias verified in cli.rs:574. |
| `--noize` / `AETHER_NOIZE` | firewall (MASQUE) / balanced (WG, gool) | **A** | Obfuscation dropdown: Off, Light, Firewall, Balanced, Aggressive, GFW — all six v2.3.0 profiles. v2.3.0 makes Firewall and GFW real (they previously fell through to Balanced; aethernoize.rs:149-154). Regression tests pin Firewall≠Balanced and GFW≠Balanced at the mapping level. |

## MASQUE transport

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--h2` / `--h3` / `AETHER_MASQUE_HTTP2` | h3 (0) | **A** | MASQUE Connection Method dropdown. **v2.3.0 fix:** now also applied for Gool over MASQUE (the outer carrier honors it: `run_gool_tunnel` uses `masque_h2::enabled()`); previously the service omitted it for protocol=gool, which on v2.3.0 hits the Core's interactive carrier prompt with closed stdin. HTTP/2 (index 1) stays the fresh/reset default (product decision, unchanged). |
| `--no-quic-v2` / `AETHER_QUIC_V2` | on | **A** | `quicV2` (H3 context). |
| `--h2-peer` / `AETHER_MASQUE_H2_PEER` | unset | **A** | `h2Peer` (H2 context). |
| `--no-data-check` / `AETHER_{MASQUE,WG}_NO_DATA_CHECK` | off | **A** | `noDataCheck` (per-side emission —). |
| `--validate-secs` / `AETHER_{MASQUE,WG}_VALIDATE_SECS` | 10 | **A** | `validateSecs` (—). |
| `--startup-secs` / `AETHER_MASQUE_STARTUP_SECS` | 30 | **A** | `startupSecs`; now also applied for Gool over MASQUE (the outer MASQUE startup budget). |
| `--reconnect-secs` / `AETHER_{MASQUE,WG}_RECONNECT_SECS` | 2 | **A** | `reconnectSecs` (—). |
| `--dns` / `AETHER_DNS` | 1.1.1.1,1.0.0.1 | **A** | `dns` (—). |
| `--fragment` / `AETHER_MASQUE_H2_FRAGMENT` | **off** (fragment.rs:33-35; help text "on by default" is inaccurate — release notes agree) | **A** | `h2Fragment` (H2-only context; default off). |
| `--fragment-size` / `AETHER_MASQUE_H2_FRAGMENT_SIZE` | **8-16** (changed from 16-32 in v2.1.0) | **A** | `h2FragmentSize`; default updated to the v2.3.0 upstream default with a value-preserving migration (users whose stored value equals the old default are moved to the new default; explicit user values are never touched). |
| `--fragment-delay` / `AETHER_MASQUE_H2_FRAGMENT_DELAY` | 2-10 | **A** | `h2FragmentDelay` (—). |
| `AETHER_MASQUE_H2_FRAGMENT_SNI` (undocumented in help, real in source) | off | **A** | New `h2FragmentSni` expert toggle: split the ClientHello inside the server name rather than at a random offset. |
| `AETHER_MASQUE_MTU` (undocumented, real) | auto | **A** | VPN MTU pref → internal (MIM fixed 1400) —; now also applied for Gool over MASQUE (same outer-carrier semantics). |

## TLS

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--ech` / `AETHER_ECH` | off | **A** | `ech` (Auto / Off / Custom base64). v2.3.0 applies ECH to the MASQUE handshakes (H2 and H3) **and** the WARP API calls, for masque, mim and Gool over MASQUE (each starts an `EchSession`; classic gool and wg do not). Fail-closed: with ECH asked for and no usable key the session does not start ("the server name never goes out in the clear") — the app surfaces the Core's real error. |
| `--ech-dns` / `AETHER_ECH_DNS` | udp://1.1.1.1 | **A** | New `echDns` (Auto context — advanced ECH): resolver URL `udp://ip[:port]`, `tcp://ip[:port]`, or `https://…` with optional `@address=` / `@sni=`; validated before Core start. |
| `--ech-domain` / `AETHER_ECH_DOMAIN` | cloudflare-ech.com | **A** | New `echDomain` (Auto context — advanced ECH): domain-validated. |
| `--tls-ciphers` / `AETHER_TLS_CIPHERS` | Chrome's `ALL:!aPSK:!ECDSA+SHA1:!3DES` (H3: none — QUIC is TLS 1.3 only) | **A** | New `tlsCiphers` (Core Default / Custom colon-separated BoringSSL TLS 1.2 list). The Core validates at startup (`check_tls_options` — a bad value stops the Core with the option named); the app also pre-validates shape and surfaces the Core's error. |
| `--tls-groups` / `AETHER_TLS_GROUPS` | `P-256:X25519:P-384` | **A** | New `tlsGroups` (Core Default / Custom ordered list). |
| `--disable-grease` / `AETHER_DISABLE_GREASE` | off (GREASE on) | **A** | New `grease` consumer toggle "GREASE" (on by default, matching upstream; off → `AETHER_DISABLE_GREASE=1`). Mapping direction is exact, not inverted. |
| `--tls-verify` / `AETHER_TLS_VERIFY` | off | **A** | New `tlsVerify` "TLS Certificate Verification" (default off = upstream). Semantics documented truthfully from tls.rs: enables **pin-based SPKI SHA-256 verification against the Core's built-in Cloudflare MASQUE pins** (`consts::MASQUE_PINS`) — not standard CA chain validation, not user pinning; with no pins applicable it stays "none". Not listed in the v2.3.0 `--help` text but real in cli.rs:630 and tls.rs — discrepancy recorded here. |
| 4th API fingerprint (TLS 1.3 + fragmented ClientHello, split inside SNI) | automatic | **B** | Automatic Core behavior; no user-facing knob exists in v2.3.0 (audited: `--api-fragment` only forces the fragmented route; the fingerprint itself is internal). Aethon does not override or prevent it. |

## WireGuard

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--keepalive` / `AETHER_WG_KEEPALIVE` | 5 | **A** | `wgKeepalive` (wg/gool context). |
| `--no-profile-retry` / `AETHER_WG_NO_PROFILE_RETRY` | off | **A** | `wgNoProfileRetry` (—). |
| `AETHER_WG_ENDPOINT_COOLDOWN_SECS` | 300 | **A** | New expert `wgEndpointCooldownSecs`. |
| `AETHER_WG_STALE_SECS` | 10 | **A** | New expert `wgStaleSecs`. |

## Tor

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--tor` / `AETHER_TOR=chain` | off | **A** | Tor chain (combined +Tor protocols / `torMode=chain`); the base SOCKS moves to the internal underlay and Tor is the final device egress. v2.3.0 physical proof: WG+Tor, MASQUE H2+Tor, Gool-over-MASQUE+Tor, and Classic Gool+Tor all bootstrapped, carried real traffic, and tore down cleanly on dev.036/dev.037. |
| `--tor-reverse` / `AETHER_TOR=reverse` | off | **E** (gated model) | The Core runs it only over MASQUE H2 and refuses `--wg`/`--gool`. Technically integrable, but no physical evidence exists (any version) that a full-device reverse-Tor session works end to end on Android; the product refuses to claim unproven topologies. Modeled in the settings layer and hard-blocked pending physical proof; documented as an honest gap, not "not needed". |
| `--tor-only` / `AETHER_TOR=only` | off | **E** (gated model) | Same gating rationale: plain Tor on the public ports with no tunnel; bootstrap-dependent (dev.033 Windows evidence: this network blocks Tor consensus). Modeled and blocked pending physical proof. |
| `--tor-bind` / `AETHER_TOR_BIND` | 127.0.0.1:1820 | **B** | Internal (side proxy 1821 / chain-final contract — ). |
| `--tor-http` / `AETHER_TOR_HTTP` | off | **A** | `torHttp` (side topology; auto-provisioned in chain mode for the public 1818 contract). Chain HTTP contract physically covered by the Tor-chain real-traffic proof; side-only auxiliary controls remain available through the existing capability model. |
| `--tor-bridge-file` / `AETHER_TOR_BRIDGE_FILE` | unset | **A** (mapping/UI; bridge-specific runtime not separately exercised) | `torBridgeMode=file` + `torBridgeUri` (SAF document import, staged privately). The field/mapping is complete; bridge-file bootstrap is recorded as a separate runtime evidence gap. |
| `--tor-relays` / `AETHER_TOR_RELAYS` | auto | **A** (chain runtime proven; policy variants not separately exercised) | `torRelayPolicy` (default / off / additional / only) + `torRelayCount` → `auto` stays unset, `only:`, count, `off`. v2.3.0 onionoo/bridge semantics are mapped; the final chain matrix proves the default relay path. |
| `--tor-relay-ports` / `AETHER_TOR_RELAY_PORTS` | web | **C** | `web` (80/443 only) is the only sensible choice behind a restrictive mobile network; `any` widens the scan surface with no evidence of benefit on Android, and the ports "tor itself is known for are always skipped" upstream. |
| `--tor-dir` / `AETHER_TOR_DIR` | `<config>-tor` | **B** | Internal private dir. |
| `--tor-bridges` / `--no-tor-bridges` / `AETHER_TOR_BRIDGES` | auto | **A** | Bridge strategy: policy-driven (off / auto with relays / file import). |
| `--tor-bridge <line>` / `AETHER_TOR_BRIDGES` (multi) | — | **A** | Custom bridge lines enter through the bridge-file import (a bridge file is the torrc-shaped multi-line form the Core reads); a separate single-line paste field would duplicate the same env var. |
| `--tor-pt` / `AETHER_TOR_PT` | auto-discovery | **B** | Internal: lyrebird (integrity-verified from nativeLibraryDir) mapped for obfs4/snowflake/webtunnel/meek_lite/obfs3/scramblesuit. |
| `--tor-pt-dir` / `AETHER_TOR_PT_DIR` | — | **C** | Redundant on Android: the PT is packaged and verified at a fixed app-native path. |
| `AETHER_TOR_DIRECT_SECS` / `STALL_SECS` / `BRIDGE_SECS` | 75 / 75 / 360 | **C** | Bootstrap timing budgets with upstream-tuned defaults; no Android-specific tuning evidence. Physical dev.034 validation measures the defaults rather than exposing them. |
| `AETHER_TOR_COUNTRY` | unset | **C** | BridgeDB country request; the relay pool is far larger (onionoo) and country-pinned bridge requests add no verified value on Android. |
| `AETHER_TOR_CHECK` | check.torproject.org:443 | **C** | Readiness check target; the Core default is the canonical check. |
| `AETHER_TOR_LOG` | info | **C** | Tor-side log verbosity; the service already surfaces bootstrap progression lines at info. |

## Psiphon

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--psiphon` / `AETHER_PSIPHON=chain` | off | **A** | Psiphon chain (combined +Psiphon protocols / `psiphonMode=chain`); final device egress. v2.3.0 physical proof: WG+Psiphon, MASQUE H2+Psiphon, Classic Gool+Psiphon, and Gool-over-MASQUE+Psiphon (dev.034/dev.036) carried real traffic and tore down cleanly. The dev.037 midday Gool+Psiphon window had a bounded meek-front refusal while the identical topology is already physically verified. |
| `--psiphon-reverse` / `AETHER_PSIPHON=reverse` | off | **E** (gated model) | Core requires the MASQUE H2 carrier and refuses `--wg`/`--gool`. Modeled (`psiphonMode=reverse`); v2.1.0 evidence marked it unsupported/unverified, and no full-device Android evidence exists. Blocked pending the dev.034 physical re-test — not silently dropped. |
| `--psiphon-only` / `AETHER_PSIPHON=only` | off | **E** (gated model) | v2.1.0 physical evidence: bootstrap DNS failure, readiness incomplete. The v2.3.0 Android CA-store fix may resolve it; re-test on dev.034 decides, and the capability flag flips only with data-plane proof. |
| `--psiphon-mode` / `AETHER_PSIPHON_MODE` | auto | **A** | `psiphonTransport` (Auto / Direct / CDN). Auto re-verified on v2.3.0 physical chains; CDN stays blocked on its prior 0/6 runtime evidence (v2.3.0 re-test not separately run). |
| `--psiphon-config` / `AETHER_PSIPHON_CONFIG` | unset | **C** | Raw JSON credential/server-list overlay. The product contract is the Core's built-in embedded credentials (that is upstream's own "nothing to configure" design); a malformed overlay bricks the helper with no consumer-safe validation surface, and no Android user demand exists for swapping Psiphon credentials. Future implementation is possible (SAF import + schema validation). |
| `--psiphon-cdn-ips/sni/sets` | built-in lists | **C** | Deep CDN-fronting overrides that only matter when CDN mode itself is selectable; it is blocked on runtime evidence. Revisit together with CDN if the v2.3.0 re-test flips it. |
| `--psiphon-bind` / `AETHER_PSIPHON_BIND` | 127.0.0.1:1821 | **B** | Internal (1822 chain contract). |
| `--psiphon-http` / `AETHER_PSIPHON_HTTP` | off | **A** | `psiphonHttp` (side topologies; auto-provisioned in chain mode for the public contract). |
| `--psiphon-region` / `AETHER_PSIPHON_REGION` | unset | **A** | Psiphon region selector (—). LOCATION shows the final Psiphon egress. |
| `--psiphon-dir` / `AETHER_PSIPHON_DIR` | `<config>-psiphon` | **B** | Internal private dir (team-scoped). |
| `--psiphon-bin` / `AETHER_PSIPHON_BIN` | auto (pt/, PATH) | **B** | Internal: integrity-verified helper from nativeLibraryDir. |
| `--psiphon-server-entries` | unset | **C** | First-connection bootstrap file; the built-in embedded server list is the product contract (upstream: "connects on a fresh machine"). |
| `AETHER_PSIPHON_READY_SECS` | 180 | **C** | Bootstrap timeout; upstream-tuned, no Android evidence for changing it. |
| `AETHER_PSIPHON_INTERFACE` / `_SPONSOR_ID` / `_PROPAGATION_CHANNEL_ID` / `_SERVER_LIST` | internal | **C** | Deep deployment overrides for alternate Psiphon infrastructure; not part of the official embedded-credential contract the product uses. |
| Android CA store (release fix) | native detection | **B (physically verified)** | v2.3.0 finds `/apex/com.android.conscrypt/cacerts` + `/system/etc/security/cacerts` itself and exports `SSL_CERT_DIR` **only when neither SSL var is pre-set**. Aethon's obsolete manual `SSL_CERT_DIR` injection is removed; live Psiphon logs on dev.034/dev.036/dev.037 prove the native store path. |

## Zero Trust

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--team` / `AETHER_TEAM` | unset | **A** | `team` (—, per-team identity file). |
| `--access-id` / `--access-secret` | unset | **A** | `accessClientId` / `accessClientSecret` (headless service-token enrolment, both-or-neither validated). |
| `--access-token` / `AETHER_ACCESS_TOKEN` | unset | **A** | `accessToken` (pre-obtained enrolment token). |
| `--access-email` / `AETHER_ACCESS_EMAIL` | unset | **E** | Upstream implements interactive email OTP through the terminal prompt (stdin); the Android service has no interactive stdin by design. The UI therefore does not offer email login — it exposes the token and service-token flows, and validation requires one of them when a team is set. |
| `--gateway` / `AETHER_GATEWAY` | off | **A** | `gateway` (team context). |

## Routing

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--route-block` / `AETHER_ROUTE_BLOCK` | empty | **A** | `routeBlock` (validated rule list). |
| `--route-direct` / `AETHER_ROUTE_DIRECT` | empty | **A** | `routeDirect`. Help documents that route-direct bypasses the tunnel for matching traffic; the UI help states the privacy tradeoff explicitly (system VPN still carries it per Android VPN rules; the Core routes it outside the tunnel). |
| `--routes` / `AETHER_ROUTES_FILE` | unset | **C** | Redundant file form of the two lists; the product exposes the lists inline. |
| `AETHER_ROUTE_SNIFF` / `AETHER_ROUTE_SNIFF_MS` | on / 400 | **A** | `routeSniff` / `routeSniffMs` (—). |

## Identity / config

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--config` / `AETHER_CONFIG` | aether.toml | **B** | Internal identity path (team-scoped variants). |
| `--wg-config` / `--masque-config` | derived | **B** | Internal sibling-path derivation (incl. the gool inner identity `-gool.toml` in v2.3.0). |
| `--register` / `AETHER_REGISTER` | unset | **C** | Identity provisioning tool-mode; the product owns the identity lifecycle (automatic registration on first connect, reprovision on refusal). No user action exists that needs a register-and-exit run on Android. |
| `--enroll-address` / `AETHER_ENROLL_ADDRESS` | api.cloudflareclient.com | **A** | New `enrollAddress` (Advanced Identity & WARP): `ip|name[:port]`, IPv6 in brackets; the server name and HTTP host stay `api.cloudflareclient.com` upstream. Validated before Core start. |
| `AETHER_REPROVISION` | on | **A** | New `reprovision` toggle (default on): off → `AETHER_REPROVISION=0` stops replacing a refused identity with a fresh registration. |
| WARP identity registration through MASQUE (gool) | automatic | **B** | v2.3.0 registers the gool WireGuard identity through the MASQUE tunnel itself (temporary socks5h upstream; proof lines captured). Aethon performs no second Android-side registration that could bypass it. |
| ECH on the WARP API flow | with `--ech` | **B** | Same `AETHER_ECH` contract covers the WARP API calls; no separate control exists upstream. |

## Performance / reliability

| Flag / env | Default | Classification | Android mapping |
| --- | --- | --- | --- |
| `--perf` / `AETHER_PERF_PROFILE` | auto (cpu/ram detect) | **A** | New `perfProfile` (Auto / Low / Medium / High) under Expert. |
| `--log-level` / `AETHER_LOG_LEVEL` | info | **B** | Fixed `info` by product diagnostic policy (same as every prior dev build; connection stages, validation, reconnects are all surfaced). |
| `--verbose` / `RUST_LOG` | — | **C** | Debug verbosity; no user-facing log surface in the product. |
| `AETHER_TCP_CONNECT_SECS` | 30 | **A** | New expert `tcpConnectSecs` (tunnel connect budget; user-useful on slow mobile networks). |
| `AETHER_TCP_KEEPALIVE_SECS` | 60 | **C** | Idle keepalive cadence; upstream default, no mobile evidence for changing it. |
| `AETHER_HALF_CLOSE_SECS` | 30 | **C** | Half-close idle window; no Android-specific value. |
| `AETHER_MAX_CLIENTS` | resource-based (512–8192) | **C** | Concurrent local proxy clients; a phone generates a handful of connections, far below the minimum default. |
| `AETHER_IRONCLAD_PORT` | 80 | **C** | Ironclad scan HTTP check port; scan-policy detail with a canonical default. |
| `AETHER_MASQUE_H2_KEEPALIVE_SECS` / `…_TIMEOUT_SECS` | 15 / 20 | **A** | New expert `h2KeepaliveSecs` / `h2KeepaliveTimeoutSecs` (H2 context). |
| `AETHER_NETSTACK_TCP_RX` / `_TX` | sysprofile-managed | **C** | Netstack buffer overrides under the resource-profile system; exposing raw buffer bytes to consumers is the definition of dangerous tuning with no measured Android benefit. |
| Quick-reconnect / last-8-gateway cache / H2-H3 carrier separation | on | **B** | v2.3.0 remembers the last eight working gateways and never reuses an H3-proven gateway on H2 (`lastconn.rs`); the Aethon endpoint cache is reconciled to stay advisory-only (the Core's own cache is authoritative) and no cross-carrier state is synthesized. |
| H2 data-plane scan fix (~1s first gateway) | automatic | **B** | Core behavior; Aethon keeps no retry/timeout workaround that would negate it (verified: no H2-specific retry layer exists in the service). |

## Environment hygiene

`CoreSettings.clearInheritedEnvironment` strips all `AETHER_*`, `RUST_LOG`, `SSL_CERT_FILE`
and `SSL_CERT_DIR` from the inherited process environment before the Core's own map is
applied — unchanged, and the reason the v2.3.0 native Android CA detection (which yields
to a pre-set variable) can govern.

## Zero-unclassified check

Every flag in the executed v2.3.0 `--help` and every real (non-test) `AETHER_*` variable
in the exact tag source is classified above. Test-only variables (AETHER_DOH_*_TEST_*,
AETHER_PROBE_*) and the internally-set `AETHER_TEAM_ENDPOINT` are excluded from the user
surface by source evidence (cfg(test) sections; set_var in lib.rs).
