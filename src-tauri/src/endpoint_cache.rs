//! Reuse of the warp-in-warp endpoints the core selected on the previous connect.
//!
//! The core ships its own endpoint cache (`AETHER_QUICK_RECONNECT` plus a
//! `-lastconn.toml` beside the identity config), but it is only wired into
//! `run_masque` and `run_wireguard`. The `Protocol::WarpInWarp` arm calls
//! `run_gool` without a `lastconn` path, so the cache is never read or written
//! for gool. Windows defaults to gool, which is why the shipped telemetry
//! records a cache hit rate of exactly zero across every retained attempt: the
//! setting was honoured, it just does not reach the protocol Windows uses.
//!
//! Aether v2.0.0 retains `AETHER_WIW_PEERS`, which lets the caller hand both hops
//! back to the core, so the reuse can live here instead of waiting on upstream.
//!
//! A pin is a *hint*, never a shortcut. Four rules make that true, and each one
//! exists because of something the core does:
//!
//! * **Only endpoints that reached `Connected` are ever stored.** The reader
//!   observes the pair; nothing is written until the same attempt has passed the
//!   HTTPS data-plane probe, so a pair that merely opened a listener is not
//!   remembered.
//! * **A pin never shortens validation.** The cached attempt runs exactly the
//!   same probes as a scanned one.
//! * **A pin that does not work is deleted at once.** v2.0.0's `run_gool`
//!   deliberately never blacklists a hand-pinned hop - it logs "still retrying
//!   them" and keeps going - so a stale pin would otherwise be retried until the
//!   stall timeout. Invalidation has to happen on this side.
//! * **Values are re-parsed as `SocketAddr` before they are used.** The pair is
//!   recovered from a core log line, so treating it as opaque text would let a
//!   malformed line put arbitrary content into a child process environment.
//!   Parsing and re-rendering canonically removes that.
//!
//! The network a pin belongs to is resolved *once*, by the caller, before the
//! core starts - see `network_fingerprint`. Both `load` and `store` are handed
//! that one value rather than each computing its own, because the two calls
//! happen on opposite sides of the tunnel coming up and would otherwise be
//! describing different interfaces.

use serde::{Deserialize, Serialize};
use std::{
    net::SocketAddr,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

/// How long a pin may be reused before a full scan is forced anyway.
///
/// Cloudflare rotates edge capacity and a client's own path to it changes; a
/// pin that is hours old is more likely to be slow than absent. This is the
/// backstop for the case where a stale pin keeps *working* but has quietly
/// become a bad choice - the failure path does not cover that, because there is
/// no failure.
const MAX_AGE_MS: u64 = 6 * 60 * 60 * 1000;

const FILE_NAME: &str = "gool-endpoints.json";
const RECORD_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Endpoints {
    pub outer: SocketAddr,
    pub inner: SocketAddr,
}

impl Endpoints {
    /// The value handed to the core. `AETHER_WIW_PEERS` is positional: first
    /// entry is the outer hop, second is the inner one.
    pub fn as_wiw_peers(&self) -> String {
        format!("{},{}", self.outer, self.inner)
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Record {
    version: u32,
    outer: String,
    inner: String,
    /// Which network the pair was selected on - see `network_fingerprint`.
    network: String,
    scan_mode: String,
    core_version: String,
    saved_unix_ms: u64,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis().min(u64::MAX as u128) as u64)
        .unwrap_or(0)
}

pub fn cache_path(data_dir: &Path) -> PathBuf {
    data_dir.join(FILE_NAME)
}

/// Recover both hops from the line the core prints once warp-in-warp is up:
///
/// ```text
/// [+] using cloudflare edge 188.114.97.3:1701 (outer) and 162.159.192.7:2408 (inner)
/// ```
///
/// Returns `None` for anything that is not exactly that shape with two parsable
/// socket addresses, so a truncated or reworded line is ignored rather than
/// half-understood.
pub fn capture_from_log(line: &str) -> Option<Endpoints> {
    let lower = line.to_ascii_lowercase();
    if !lower.contains("cloudflare edge") || !lower.contains("(outer)") || !lower.contains("(inner)")
    {
        return None;
    }
    let outer = socket_before(line, "(outer)")?;
    let inner = socket_before(line, "(inner)")?;
    if outer == inner {
        return None;
    }
    Some(Endpoints { outer, inner })
}

/// The last socket address appearing before `marker`.
fn socket_before(line: &str, marker: &str) -> Option<SocketAddr> {
    let lower = line.to_ascii_lowercase();
    let cut = lower.find(marker)?;
    line[..cut]
        .split_whitespace()
        .filter_map(|token| {
            token
                .trim_matches(|c: char| !c.is_ascii_hexdigit() && !matches!(c, '.' | ':' | '[' | ']'))
                .parse::<SocketAddr>()
                .ok()
        })
        .next_back()
}

/// Load a pin that is still eligible for this connect.
///
/// `network` is the fingerprint of the interface this machine will dial out on,
/// resolved by the caller before the core starts.
///
/// Returns `None` - meaning "scan normally" - whenever anything about the
/// recorded pair no longer matches the attempt about to be made: a different
/// scan mode selects from a different candidate set, a different core version
/// may select differently, a different network says nothing about this one, and
/// an expired record is not trusted on age alone.
pub fn load(
    data_dir: &Path,
    network: &str,
    scan_mode: &str,
    core_version: &str,
) -> Option<Endpoints> {
    let raw = std::fs::read(cache_path(data_dir)).ok()?;
    let record: Record = serde_json::from_slice(&raw).ok()?;
    if record.version != RECORD_VERSION
        || record.scan_mode != scan_mode
        || record.core_version != core_version
        || record.network != network
    {
        return None;
    }
    if now_ms().saturating_sub(record.saved_unix_ms) > MAX_AGE_MS {
        return None;
    }
    let outer = record.outer.parse::<SocketAddr>().ok()?;
    let inner = record.inner.parse::<SocketAddr>().ok()?;
    if outer == inner {
        return None;
    }
    Some(Endpoints { outer, inner })
}

/// Remember a pair. Only ever called for an attempt that reached `Connected`.
///
/// `network` must be the fingerprint captured *before* this attempt brought its
/// tunnel up. Recomputing it here would resolve the Aethon TUN adapter instead
/// of the underlying link, and a pin filed under the tunnel it created can never
/// be found again.
pub fn store(
    data_dir: &Path,
    endpoints: Endpoints,
    network: &str,
    scan_mode: &str,
    core_version: &str,
) {
    let record = Record {
        version: RECORD_VERSION,
        outer: endpoints.outer.to_string(),
        inner: endpoints.inner.to_string(),
        network: network.to_string(),
        scan_mode: scan_mode.to_string(),
        core_version: core_version.to_string(),
        saved_unix_ms: now_ms(),
    };
    if let Ok(text) = serde_json::to_vec_pretty(&record) {
        let _ = std::fs::write(cache_path(data_dir), text);
    }
}

/// Drop the pin. Called the moment a pinned attempt fails to produce a working
/// tunnel, so the next attempt scans instead of retrying a dead endpoint.
pub fn invalidate(data_dir: &Path) {
    let _ = std::fs::remove_file(cache_path(data_dir));
}

/// A cheap identity for "the network this machine currently reaches the
/// Internet through": the alias and hardware address of the interface holding
/// the default route.
///
/// It distinguishes Ethernet from Wi-Fi from a phone tether, which is the case
/// that matters - endpoints chosen on an unrestricted network say nothing about
/// a restricted one. It does *not* distinguish two Wi-Fi networks joined
/// through the same adapter; that is a known limit, and the reason a pin is
/// still validated by the normal probes rather than trusted on this alone.
///
/// **Call this before the tunnel exists.** Once Aethon's own TUN adapter holds
/// the default route, this describes the tunnel rather than the link underneath
/// it, and the adapter's alias carries a per-session suffix, so every answer
/// would differ. That case is refused rather than hashed: `None` means "this
/// network cannot be identified", and every caller treats it as "scan fully,
/// remember nothing", because reusing a pin across an unknown network is exactly
/// the mistake the fingerprint exists to prevent.
pub fn network_fingerprint() -> Option<String> {
    #[cfg(windows)]
    {
        use windows_sys::Win32::NetworkManagement::IpHelper::{
            GetBestInterface, GetIfEntry2, MIB_IF_ROW2,
        };
        let mut index = 0u32;
        if unsafe { GetBestInterface(u32::from_ne_bytes([1, 1, 1, 1]), &mut index) } != 0
            || index == 0
        {
            return None;
        }
        let mut row: MIB_IF_ROW2 = unsafe { std::mem::zeroed() };
        row.InterfaceIndex = index;
        if unsafe { GetIfEntry2(&mut row) } != 0 {
            return None;
        }
        let alias = String::from_utf16_lossy(
            &row.Alias
                .iter()
                .copied()
                .take_while(|unit| *unit != 0)
                .collect::<Vec<_>>(),
        );
        // One of our own adapters is answering, so a previous session's tunnel
        // is still up or this was called too late. Either way the answer would
        // describe the tunnel, not the network.
        if alias.starts_with("AethonTun-") || alias == "FirsthamAether" {
            return None;
        }
        let mac = row
            .PhysicalAddress
            .iter()
            .take((row.PhysicalAddressLength as usize).min(row.PhysicalAddress.len()))
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        // Hashed rather than stored plainly: the file is only a performance
        // cache and does not need to carry a readable hardware address.
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        hasher.update(alias.as_bytes());
        hasher.update(b"/");
        hasher.update(mac.as_bytes());
        Some(
            hasher
                .finalize()
                .iter()
                .take(8)
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        )
    }
    #[cfg(not(windows))]
    {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LINE: &str =
        "[+] using cloudflare edge 188.114.97.3:1701 (outer) and 162.159.192.7:2408 (inner)";

    /// Copied verbatim out of the shipped core log, log prefix and all. The
    /// reader hands whole lines through, so the timestamp and level are part of
    /// what the parser actually sees - and the ISO timestamp is exactly the kind
    /// of token a looser "find something address-shaped" scan would trip on.
    const REAL_LINE: &str = "[2026-09-10T16:55:42.933Z INFO  aether] [+] using cloudflare edge 188.114.96.75:878 (outer) and 162.159.195.181:908 (inner)";

    #[test]
    fn the_line_the_shipped_core_actually_prints_is_understood() {
        let found = capture_from_log(REAL_LINE).expect("real core line should parse");
        assert_eq!(found.outer, "188.114.96.75:878".parse().unwrap());
        assert_eq!(found.inner, "162.159.195.181:908".parse().unwrap());
    }

    #[test]
    fn both_hops_are_recovered_from_the_core_line() {
        let found = capture_from_log(LINE).expect("line should parse");
        assert_eq!(found.outer, "188.114.97.3:1701".parse().unwrap());
        assert_eq!(found.inner, "162.159.192.7:2408".parse().unwrap());
        assert_eq!(
            found.as_wiw_peers(),
            "188.114.97.3:1701,162.159.192.7:2408",
            "AETHER_WIW_PEERS is positional: outer first, inner second"
        );
    }

    #[test]
    fn unrelated_lines_are_not_mistaken_for_a_selection() {
        for line in [
            "[+] socks5 server listening on 127.0.0.1:1819",
            "[-] scanning for cloudflare edge endpoints",
            "[+] using cloudflare edge 188.114.97.3:1701 (outer)",
            "[+] using cloudflare edge notanaddress (outer) and 1.2.3.4:5 (inner)",
        ] {
            assert!(
                capture_from_log(line).is_none(),
                "should not have parsed: {line}"
            );
        }
    }

    #[test]
    fn a_pair_that_names_one_endpoint_twice_is_rejected() {
        // A single hop pinned as both ends is not warp-in-warp; reusing it
        // would collapse the second hop rather than restore the session.
        assert!(capture_from_log(
            "[+] using cloudflare edge 1.2.3.4:500 (outer) and 1.2.3.4:500 (inner)"
        )
        .is_none());
    }

    #[test]
    fn a_stored_pin_round_trips_and_a_stale_one_is_refused() {
        let dir = std::env::temp_dir().join(format!("aethon-pin-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let endpoints = capture_from_log(LINE).unwrap();
        store(&dir, endpoints, "net-a", "balanced", "2.0.0");
        assert_eq!(load(&dir, "net-a", "balanced", "2.0.0"), Some(endpoints));

        // Anything the pin was not recorded under forces a scan.
        assert_eq!(
            load(&dir, "net-a", "turbo", "2.0.0"),
            None,
            "scan mode must match"
        );
        assert_eq!(
            load(&dir, "net-a", "balanced", "1.8.0"),
            None,
            "core version matters"
        );
        assert_eq!(
            load(&dir, "net-b", "balanced", "2.0.0"),
            None,
            "a pin from another network says nothing about this one"
        );

        // Age is enforced even when everything else still matches.
        let raw = std::fs::read(cache_path(&dir)).unwrap();
        let mut record: Record = serde_json::from_slice(&raw).unwrap();
        record.saved_unix_ms = now_ms().saturating_sub(MAX_AGE_MS + 1);
        std::fs::write(cache_path(&dir), serde_json::to_vec(&record).unwrap()).unwrap();
        assert_eq!(
            load(&dir, "net-a", "balanced", "2.0.0"),
            None,
            "expired pin refused"
        );

        invalidate(&dir);
        assert!(!cache_path(&dir).exists());
        assert_eq!(load(&dir, "net-a", "balanced", "2.0.0"), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_pin_is_filed_under_the_network_the_caller_resolved_not_one_observed_later() {
        // The regression this guards against was real and shipped in the first
        // cut of this module: `store` resolved the fingerprint itself, but it
        // runs after the tunnel is up, so it recorded the Aethon TUN adapter
        // while `load` - running with the tunnel down - recorded the physical
        // link. Two stores in a row filed under different networks and the pin
        // could never be found again. Taking the value as a parameter is what
        // makes the two sides agree, so the contract is worth asserting.
        let dir = std::env::temp_dir().join(format!("aethon-pin-net-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let endpoints = capture_from_log(LINE).unwrap();
        store(&dir, endpoints, "dialled-out-on-this", "balanced", "2.0.0");

        let raw = std::fs::read(cache_path(&dir)).unwrap();
        let record: Record = serde_json::from_slice(&raw).unwrap();
        assert_eq!(
            record.network, "dialled-out-on-this",
            "store must record exactly what it was handed"
        );
        assert_eq!(
            load(&dir, "dialled-out-on-this", "balanced", "2.0.0"),
            Some(endpoints),
            "a pin written on a network must be readable on that same network"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_unidentifiable_network_is_refused_rather_than_lumped_together() {
        // `network_fingerprint` returns None when it cannot name the link, and
        // the callers turn that into "scan fully, remember nothing". If it
        // returned a placeholder instead, every unidentifiable network would
        // share one bucket and pins would leak between them.
        let dir = std::env::temp_dir().join(format!("aethon-pin-unk-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        store(&dir, capture_from_log(LINE).unwrap(), "", "balanced", "2.0.0");
        // Nothing else in the module invents a fingerprint, so an empty network
        // can only be reached by a caller that chose to pass one through.
        assert_eq!(load(&dir, "real-net", "balanced", "2.0.0"), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_invalidated_pin_leaves_nothing_behind_to_retry() {
        // v2.0.0's run_gool never blacklists a hand-pinned hop, so a pin that
        // survived its own failure would be retried until the stall timeout.
        let dir = std::env::temp_dir().join(format!("aethon-pin-drop-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        store(
            &dir,
            capture_from_log(LINE).unwrap(),
            "net-a",
            "balanced",
            "2.0.0",
        );
        assert!(cache_path(&dir).exists());
        invalidate(&dir);
        assert!(!cache_path(&dir).exists());
        invalidate(&dir); // idempotent: a missing file is not an error
        let _ = std::fs::remove_dir_all(&dir);
    }
}
