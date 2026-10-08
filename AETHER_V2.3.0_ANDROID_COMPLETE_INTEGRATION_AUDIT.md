# Aether v2.3.0 Android — Complete Integration Audit

**Scope:** Android Aethon dev.034 mission, final retained artifact dev.037.
**Core:** official unmodified Aether v2.3.0, tag `v2.3.0`, source commit
`6175b67df370ab856bcee07fe85b524031903956`.
**Device:** Samsung SM-A556E / Galaxy A55 / arm64-v8a.
**Evidence root:** `work/android-dev034-20261006/`.

## Audit conclusion

The Aether v2.3.0 Android integration is complete and physically exercised. The native Core
and helper payloads are official and hash-pinned for ARM64, ARMv7, and x86_64. The Android
layer maps the v2.3.0 environment surface, persists and migrates settings, exposes the
supported controls in the existing dev.020 UI, enforces capability gates, proves Core
runtime identity before use, and verifies real device traffic before publishing Connected.

The final retained build is `dev.037`, built with 303 passing unit tests and zero lint
errors/fatals. Two timing defects found through physical validation were corrected in the
final build: MASQUE-carrier Psiphon bootstrap allowance and privacy-chain probe budget.
Remaining failed records are strict environmental classifications or bounded helper
bootstrap failures, with no silent fallback.

## Native provenance and integrity

The approved native-input chain is recorded in:

- `work/android-dev034-20261006/NATIVE_INPUTS.json`
- `work/android-dev034-20261006/verified-native-inputs.json`
- `work/android-dev034-20261006/verified-helper-inputs.json`
- `scripts/aether-pins.json`
- `development-builds/dev.037/build-manifest.json`

For each ABI, the builder verifies ELF class/type/ABI, byte count, SHA-256, official Core
version marker, APK embedded native hashes, signatures, and 16-KiB alignment. The builder
also checks that source/native inputs do not change during the build.

| ABI | Payloads |
| --- | --- |
| `arm64-v8a` | official `libaether.so` v2.3.0 + `libhev-socks5-tunnel.so` + `libpsiphon-tunnel-core.so` + `liblyrebird.so` |
| `armeabi-v7a` | same official set, ABI-specific binaries |
| `x86_64` | same official set, ABI-specific binaries |

`dev.037` artifacts:

| ABI set | APK | SHA-256 |
| --- | --- | --- |
| Universal | `Aethon-v2.1.2-dev.037-Android-Universal.apk` | `38d34d7f45fcb13be13a153f5c58f2d213a22ddcc12f2eeed698793b70b02a58` |
| ARMv7 | `Aethon-v2.1.2-dev.037-Android-ARMv7.apk` | `ad5b5972b88294ef601882df8abb93e9bdc3ed2f0508308c7c1143120668be15` |
| ARM64 | `Aethon-v2.1.2-dev.037-Android-ARM64.apk` | `565f5561b303e6f90ca939497bdf670c4e4019321087bc725cbb14e373fcec62` |
| x86_64 | `Aethon-v2.1.2-dev.037-Android-x86_64.apk` | `7f4311a4b5b4fc2217dbd1e11ed650dccaa4fc2f3e00bca97e3eda403e99f3f1` |

Package: `io.github.hamvex.aethergui`; versionName `2.1.2-dev.037`; versionCode `28`.
Signer SHA-256: `68a8995c8e1bcb6d4bd5cc73369e29edc3f6e8bd94e1a7c3142a96e73be49d3d`.

## Android integration surface

### Runtime and lifecycle

- Core is copied from the verified native input set into the app's native library directory.
- Pre-start integrity verifies the selected ABI's Core hash and embedded v2.3.0 identity.
- The live service logs both `Aether v2.3.0` and the expected SHA-256 proof before routing.
- Core output is read continuously; topology announcements are parsed from Core's own proof
  lines, never inferred from requested UI mode.
- The service uses bounded connect watchdogs, bounded teardown, controlled retry, listener
  collision checks, endpoint re-rolls, traffic gates, and zero-orphan cleanup.
- Real HTTPS/SOCKS/HTTP traffic must prove before Connected is published.
- Privacy helper announcements are awaited before privacy endpoints are probed.

### Core v2.3.0 environment mapping

The mapping is implemented in `CoreSettings`, `PrivacyRuntimeConfig`, `PrivacySettings`,
and the service environment assembly. All exposed values are validated before Core launch;
invalid values stop before native process startup and surface a bounded UI error.

| v2.3.0 area | Android mapping | Physical status |
| --- | --- | --- |
| `--protocol` | protocol selector + atomic chain state | WG, MASQUE, MIM, Gool topologies, chains, Tor physically exercised |
| `--scan` | Scan Mode | Turbo default and runtime scans proven |
| `--noize` | Noize selector → `AETHER_NOIZE` | Firewall and GFW starts pass |
| `--ip` | IP mode | existing selector preserved |
| `--ech` | Off/Auto/Custom UI → `AETHER_ECH` | Off pass; Auto lookup path proven; full handshake network-blocked; invalid custom rejected |
| `--ech-dns` | Auto resolver field → `AETHER_ECH_DNS` | DoH ECHConfigList fetch (71 bytes) proven |
| `--ech-domain` | Auto domain field → `AETHER_ECH_DOMAIN` | validation/mapping unit-tested |
| `--tls-ciphers` | expert field → `AETHER_TLS_CIPHERS` | mapping and invalid rejection unit-tested |
| `--tls-groups` | expert field → `AETHER_TLS_GROUPS` | valid physical connect and invalid rejection |
| `--disable-grease` | GREASE toggle | valid physical connect with GREASE off |
| `--tls-verify` | TLS verify toggle | physical custom TLS path |
| `--fragment-size`, `--fragment-delay`, `--fragment-sni` | H2 fragmentation controls | UI/persistence/mapping tests; baseline migration; carrier path exercised |
| `--masque-http2`, H2 keepalive | MASQUE method + reliability controls | H2 physical pass |
| H3 carrier | MASQUE selector | strict environmental block recorded; no silent downgrade for Gool |
| Gool over MASQUE | `goolMode=masque`, inner peer | Core proof, real traffic, stress pass |
| Classic Gool | `goolMode=classic`, WiW peers | Core proof, real traffic, stress pass |
| WARP enrollment/reprovision | expert fields → `AETHER_ENROLL_ADDRESS`, reprovision | mapping and Core startup exercised |
| performance profile | `AETHER_PERF_PROFILE` | mapping and runtime profile lines proven |
| stats | `AETHER_STATS` | opt-in mapping; Android VpnService telemetry remains authoritative |
| exit location | `AETHER_EXIT_LOC`, `AETHER_EXIT_LOC_SECS` | OFF physically proven; enabled policy gated pending dedicated exposure/runtime mission |
| Psiphon Auto/direct/region/HTTP | `AETHER_PSIPHON*` | WG and Classic Gool physical pass; MASQUE H2 pass on dev.036/dev.037; Gool+Psiphon verified on dev.034/dev.036, final-window meek block recorded |
| Tor chain | `AETHER_TOR=chain`, bind/relays/bridges/HTTP | WG, MASQUE H2, Gool MASQUE, Classic Gool physically bootstrapped and traffic-proven on dev.037/dev.036 |
| upstream proxy / Zero Trust / routing | validated Core settings | code + resource + persistence mappings complete; capabilities requiring credentials/network are classified |

## Gool topology correctness

The two v2.3.0 Gool topologies are one stored protocol family with an explicit persisted
`goolMode`:

- `masque`: Core receives the MASQUE carrier plus `AETHER_GOOL_INNER`; runtime proof is
  `gool ready: masque … carries wireguard …` and the UI remains strict.
- `classic`: Core receives `AETHER_GOOL_MODE=classic` and the WiW outer/inner peers;
  runtime proof is `establishing inner WARP tunnel (warp-in-warp)`.

The app records the running topology from Core output. A requested mode is never reported as
running until Core proves it. This is why H3/Gool network failures are classified rather than
reported as a fallback success.

## Psiphon Android CA-store integration

The obsolete manual `SSL_CERT_DIR` injection was removed. The official v2.3.0 Core detects
the Android stores itself and the live Core log proves:

`psiphon trusts the ca store at /system/etc/security/cacerts:/apex/com.android.conscrypt/cacerts`

No pre-set SSL certificate variables are injected by Aethon; inherited certificate variables
are cleared before launch. The behavior is covered by `PrivacyRuntimeAssetsTest` and the
physical Psiphon sessions.

## Timing fixes discovered through physical validation

### MASQUE-carrier Psiphon announcement allowance

`MASQUE_CHAIN_STARTUP_ALLOWANCE_MS=120000` is applied only when the explicit protocol is a
MASQUE carrier and `psiphonMode=chain`. It is added to both the connect watchdog and the
privacy-listener announcement wait. The protocol travels explicitly beside the settings map;
it is not read from the map because `protocol` is not a CoreSettings default key. The regression
test pins this real runtime shape.

### Privacy-chain traffic probe budget

`CHAIN_TRAFFIC_READY_TIMEOUT_MS=12000` applies only to privacy-chain endpoints. WARP-direct
probes remain at 4000ms. `worstCaseTrafficGateMs()` includes the larger chain probe budget and
the watchdog-bound test continues to assert that the gate cannot outlive the watchdog.

## Migration and persistence audit

- `AndroidCoreSettings.migrate()` preserves existing choices and repairs only conflicting
  legacy chain state.
- One-time `fragmentSizeV230` migration distinguishes old untouched defaults from explicit
  user values.
- Missing `goolMode` on a saved legacy Gool entry becomes `classic`; fresh/reset uses `masque`.
- Combined protocol selections write their base storage index plus atomic chain markers;
  this preserves the existing storage contract and makes Reset Defaults deterministic.
- Backup/restore includes every new non-secret field, excludes credentials and local-only
  material, and is covered by roundtrip tests.
- Reset Defaults preserves language, restores system theme, clears user settings, and applies
  `WireGuard + Psiphon` using base index 1 + `psiphonMode=chain`; physical verification passes.

## Security and privacy audit

- No Core fork or native patch.
- Core hash checked before spawn and runtime proof recorded.
- Credentials, access tokens, private keys, bridge secrets, and ECH material are excluded
  from normal logs/reports; the physical evidence redacts endpoint details where Psiphon
  emits them.
- No stale Core, helper, Tor/PT, HEV process, listener, or VPN state remains after the
  passing teardown cases; the final device was force-stopped and checked clean.
- Occupied-port and invalid-peer failure injection is bounded and clean.
- No downgrade to v2.1.0 and no hidden fallback is used to claim a selected topology.

## Remaining classified gaps

These are classified rather than omitted:

- MASQUE H3 gateway discovery depends on this network; strict Gool H3 never falls back.
- ECH Auto lookup is integrated and key-fetch-proven; the default UDP resolver and ECH'd
  WARP API are blocked in this environment.
- Exit-country policy is mapped and tested but remains capability-gated because user-facing
  allow/exclude controls were not exposed for this mission and the enabled policy needs a
  dedicated runtime verification set.
- Psiphon `only`, reverse, and CDN modes remain blocked by the capability matrix's exact
  native/runtime evidence; they are not silently offered.
- Gool+Psiphon's final dev.037 window had a bounded meek bootstrap failure while the same
  topology passed on earlier retained artifacts; direct cause is Psiphon front reachability,
  not a Core/app crash or fallback.

## Audit artifacts

- Final report: `AETHON_ANDROID_DEV034_FINAL_REPORT.md`
- Capability matrix: `AETHER_V2.3.0_ANDROID_CAPABILITY_MATRIX.md`
- Build history: `BUILD_HISTORY_V2.1.2.md`
- Final builder manifest: `development-builds/dev.037/build-manifest.json`
- Final checksums: `development-builds/dev.037/SHA256SUMS.txt`
- Physical evidence: `work/android-dev034-20261006/physical/results.json`
