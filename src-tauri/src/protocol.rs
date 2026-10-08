//! Canonical protocol/privacy-chain model shared by settings, process spawn, and the UI.
//!
//! Mirrors Android dev.020 `ProtocolOrder` + `PrivacyChainState`: one persisted protocol value
//! selects both the base transport and the privacy chain atomically. No dropdown indexes are
//! scattered anywhere: everything derives from this module.
//!
//! Storage values (persisted as `protocol` in settings.json):
//!   plain:   "smart" | "masque" | "wg" | "gool" | "mim"
//!   psiphon: "wg+psiphon" | "gool+psiphon" | "masque+psiphon"
//!   tor:     "wg+tor" | "gool+tor" | "masque+tor"
//!
//! Android parity decisions baked in:
//! - No "smart+psiphon" / "smart+tor" / "mim+chain" entries (Android dev.017+ deliberately).
//! - Tor is always `AETHER_TOR=chain` with `AETHER_TOR_BRIDGES=off` in chain mode.
//! - Psiphon chain mode auto-provisions the HTTP listener (1824) and suppresses the core's own
//!   HTTP proxy so the public 1818/1819 contract is fed by GUI relays from the final egress.
//!
//! gool modes (Aether Core v2.3.0, lib.rs `gool_classic()` / `AETHER_GOOL_MODE`):
//!   v2.3.0 splits gool into two materially different topologies the user must be able to
//!   choose between (dev.032 §2.4): "masque" (the new v2.3.0 default: a WireGuard tunnel
//!   carried inside a MASQUE tunnel, foreign exit) and "classic" (the v2.1.0-era
//!   WireGuard-in-WireGuard, plain WARP exit). Setting either WiW custom endpoint still
//!   auto-selects classic in-core (unchanged upstream behavior Aethon preserves).

use serde::{Deserialize, Serialize};

/// Psiphon transport. `cdn` remains blocked (fronted meek 400/404 root cause, Android evidence
/// dev.017..dev.020; reproduced on Windows in work/.../core-smoke-results.json).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[allow(dead_code)] pub enum PsiphonTransport {
    #[default]
    Auto,
    Direct,
}

/// gool topology variant (Core v2.3.0 `AETHER_GOOL_MODE`; PROMPT §2.4: two materially
/// different routing topologies must never be called the same name when the user's choice
/// between them is meaningful).
///
/// - `Masque` — the v2.3.0 default: WireGuard carried inside MASQUE ("gool over masque",
///   foreign exit address). Env value `masque` (Aethon sets nothing; the core's default).
/// - `Classic` — the v2.1.0-era gool: WireGuard-in-WireGuard, plain WARP exit. Env value
///   `classic` (core `gool_classic()` accepts `classic`|`wiw`|`wg`).
///
/// Custom WiW endpoints keep their pre-existing meaning: the core auto-selects classic when
/// any `AETHER_WIW_*_PEER` is set, and Aethon's validation still applies — the stored mode is
/// advisory and never contradicts the user's endpoint fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum GoolMode {
    /// v2.3.0 default: gool over MASQUE (no `AETHER_GOOL_MODE` needed).
    #[default]
    Masque,
    /// Classic gool: WireGuard-in-WireGuard (`AETHER_GOOL_MODE=classic`).
    Classic,
}

impl GoolMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Masque => "masque",
            Self::Classic => "classic",
        }
    }

    /// Parse the stored value. Unknown values repair to the v2.3.0 default (official Core
    /// default for gool without custom endpoints), never to a rejected connect.
    pub fn parse(value: &str) -> Self {
        match value {
            "classic" | "wiw" | "wg" => Self::Classic,
            _ => Self::Masque,
        }
    }

    /// The `AETHER_GOOL_MODE` value to emit, if any. `Masque` is the core's own default, so
    /// emitting nothing keeps the setting identical to a fresh core invocation.
    pub fn env_value(&self) -> Option<&'static str> {
        match self {
            Self::Masque => None,
            Self::Classic => Some("classic"),
        }
    }
}

impl PsiphonTransport {
    #[allow(dead_code)] pub fn as_str(&self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Direct => "direct",
        }
    }
    #[allow(dead_code)] pub fn parse(value: &str) -> Option<Self> {
        match value {
            "auto" => Some(Self::Auto),
            "direct" => Some(Self::Direct),
            _ => None,
        }
    }
}

/// Which privacy chain (if any) the selected protocol rides.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum PrivacyChain {
    #[default]
    None,
    Psiphon,
    Tor,
}

/// The 11-entry product protocol set (Android dev.020 storage order).
pub const PROTOCOLS: [&str; 11] = [
    "masque",
    "wg",
    "gool",
    "smart",
    "mim",
    "wg+psiphon",
    "gool+psiphon",
    "masque+psiphon",
    "wg+tor",
    "gool+tor",
    "masque+tor",
];

/// Display order: Smart Connect first (Android dev.020), then bases, then combined entries.
pub const DISPLAY_ORDER: [&str; 11] = [
    "smart",
    "masque",
    "wg",
    "gool",
    "mim",
    "masque+psiphon",
    "wg+psiphon",
    "gool+psiphon",
    "masque+tor",
    "wg+tor",
    "gool+tor",
];

/// Fresh-install / Reset-Defaults protocol (Android dev.020 CHANGE 1 product decision).
pub const DEFAULT_PROTOCOL: &str = "wg+psiphon";

/// Listener contract (Android ProxyMode / PublicPortRelays parity).
pub const PUBLIC_HTTP: &str = "127.0.0.1:1818";
pub const PUBLIC_SOCKS: &str = "127.0.0.1:1819";
/// Internal underlay the core binds its base SOCKS to while a chain is active. Android uses
/// 18193; on Windows that can collide with Hyper-V/WSL excluded ranges (observed on the
/// validation host: 18135-18234 is excluded, which covers 18193 but not 1818/1819/1821/1822/
/// 1824/1825). The connect flow probes and picks a free internal port instead of assuming.
pub const INTERNAL_SOCKS_PREFERRED: &str = "127.0.0.1:18193";
pub const PSIPHON_SOCKS: &str = "127.0.0.1:1822";
pub const PSIPHON_HTTP: &str = "127.0.0.1:1824";
pub const TOR_SOCKS: &str = "127.0.0.1:1821";
pub const TOR_HTTP: &str = "127.0.0.1:1825";

/// True for valid stored protocol values.
pub fn is_known(protocol: &str) -> bool {
    PROTOCOLS.contains(&protocol)
}

/// Base transport the core should run for this stored protocol (Android `baseIndex`).
pub fn base_protocol(protocol: &str) -> &str {
    match protocol {
        "wg+psiphon" | "wg+tor" => "wg",
        "gool+psiphon" | "gool+tor" => "gool",
        "masque+psiphon" | "masque+tor" => "masque",
        other => other,
    }
}

/// Privacy chain implied by the stored protocol.
pub fn privacy_chain(protocol: &str) -> PrivacyChain {
    match protocol {
        "wg+psiphon" | "gool+psiphon" | "masque+psiphon" => PrivacyChain::Psiphon,
        "wg+tor" | "gool+tor" | "masque+tor" => PrivacyChain::Tor,
        _ => PrivacyChain::None,
    }
}

/// Combined protocol for a base + chain (Android `combinedProtocolIndex` inverse mapping).
#[allow(dead_code)] pub fn combined_protocol(base: &str, chain: PrivacyChain) -> Option<&'static str> {
    match (base, chain) {
        ("wg", PrivacyChain::Psiphon) => Some("wg+psiphon"),
        ("gool", PrivacyChain::Psiphon) => Some("gool+psiphon"),
        ("masque", PrivacyChain::Psiphon) => Some("masque+psiphon"),
        ("wg", PrivacyChain::Tor) => Some("wg+tor"),
        ("gool", PrivacyChain::Tor) => Some("gool+tor"),
        ("masque", PrivacyChain::Tor) => Some("masque+tor"),
        _ => None,
    }
}

/// The MASQUE connection-method control applies to MASQUE and MIM bases (Android
/// `ProtocolOrder.masqueTransportApplicable()`).
pub fn masque_transport_applicable(protocol: &str) -> bool {
    matches!(base_protocol(protocol), "masque" | "mim")
}

/// The gool-mode control applies to gool bases (dev.032 §2.2/§2.4). Combined entries inherit
/// it from their base.
pub fn gool_mode_applicable(protocol: &str) -> bool {
    base_protocol(protocol) == "gool"
}

/// User-facing scan-mode names (dev.032 §2.5). Core v2.3.0 renamed `stealth` to `verified`
/// upstream; the stored value stays `stealth` for compatibility (the core still parses the
/// old spelling as an alias — `ScanMode::parse` accepts both, verified against the v2.3.0
/// tag source). This table maps every stored value to its v2.3.0 user-facing label; the UI
/// renders the label and stores the value.
pub fn scan_mode_label(stored: &str) -> &'static str {
    match stored {
        "turbo" => "Turbo",
        "balanced" => "Balanced",
        "thorough" => "Thorough",
        "stealth" => "Verified",
        "ironclad" => "Ironclad",
        _ => "Balanced",
    }
}

/// Combined entries are hidden in Proxy/manual mode (Android dev.017 behavior: the chain runs
/// only in full-device mode; saved preferences are preserved).
pub fn proxy_mode_visible(protocol: &str) -> bool {
    privacy_chain(protocol) == PrivacyChain::None
}

/// Additional startup allowance per chain, seconds. Psiphon must download a server list through
/// the underlay first (observed 10-15s Android, ~9s Windows smoke); Tor needs consensus
/// (minutes on first run). The generic stall watchdog is suppressed while chain announcements
/// are outstanding so a bootstrapping helper is never killed mid-bootstrap.
pub const PSIPHON_STARTUP_ALLOWANCE_SECS: u64 = 120;
pub const TOR_STARTUP_ALLOWANCE_SECS: u64 = 300;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_protocol_values_are_unique_and_known() {
        let mut sorted = PROTOCOLS;
        sorted.sort_unstable();
        for window in sorted.windows(2) {
            assert_ne!(window[0], window[1]);
        }
        assert_eq!(PROTOCOLS.len(), 11);
        assert!(PROTOCOLS.iter().all(|p| is_known(p)));
    }

    #[test]
    fn display_order_is_a_permutation_of_storage_set() {
        let mut storage: Vec<&str> = PROTOCOLS.to_vec();
        let mut display: Vec<&str> = DISPLAY_ORDER.to_vec();
        storage.sort_unstable();
        display.sort_unstable();
        assert_eq!(storage, display);
        assert_eq!(DISPLAY_ORDER[0], "smart");
    }

    #[test]
    fn combined_entries_map_to_their_base_protocol() {
        assert_eq!(base_protocol("wg+psiphon"), "wg");
        assert_eq!(base_protocol("gool+psiphon"), "gool");
        assert_eq!(base_protocol("masque+psiphon"), "masque");
        assert_eq!(base_protocol("wg+tor"), "wg");
        assert_eq!(base_protocol("gool+tor"), "gool");
        assert_eq!(base_protocol("masque+tor"), "masque");
        for plain in ["smart", "masque", "wg", "gool", "mim"] {
            assert_eq!(base_protocol(plain), plain);
        }
    }

    #[test]
    fn privacy_chain_matches_android_contract() {
        assert_eq!(privacy_chain("wg"), PrivacyChain::None);
        assert_eq!(privacy_chain("mim"), PrivacyChain::None);
        assert_eq!(privacy_chain("smart"), PrivacyChain::None);
        assert_eq!(privacy_chain("wg+psiphon"), PrivacyChain::Psiphon);
        assert_eq!(privacy_chain("masque+psiphon"), PrivacyChain::Psiphon);
        assert_eq!(privacy_chain("wg+tor"), PrivacyChain::Tor);
        assert_eq!(privacy_chain("masque+tor"), PrivacyChain::Tor);
    }

    #[test]
    fn inverse_mapping_round_trips_every_combined_entry() {
        for chain in [PrivacyChain::Psiphon, PrivacyChain::Tor] {
            for base in ["wg", "gool", "masque"] {
                let combined = combined_protocol(base, chain).unwrap();
                assert_eq!(base_protocol(combined), base);
                assert_eq!(privacy_chain(combined), chain);
            }
        }
        // smart/mim have no combined entries (deliberate Android parity).
        assert_eq!(combined_protocol("smart", PrivacyChain::Psiphon), None);
        assert_eq!(combined_protocol("mim", PrivacyChain::Tor), None);
    }

    #[test]
    fn default_protocol_is_wireguard_plus_psiphon() {
        assert_eq!(DEFAULT_PROTOCOL, "wg+psiphon");
        assert_eq!(privacy_chain(DEFAULT_PROTOCOL), PrivacyChain::Psiphon);
        assert_eq!(base_protocol(DEFAULT_PROTOCOL), "wg");
    }

    #[test]
    fn masque_transport_applicability_matches_android() {
        assert!(masque_transport_applicable("masque"));
        assert!(masque_transport_applicable("masque+psiphon"));
        assert!(masque_transport_applicable("masque+tor"));
        assert!(masque_transport_applicable("mim"));
        assert!(!masque_transport_applicable("wg"));
        assert!(!masque_transport_applicable("wg+psiphon"));
        assert!(!masque_transport_applicable("gool+tor"));
        assert!(!masque_transport_applicable("smart"));
    }

    #[test]
    fn gool_mode_applies_to_gool_bases_only() {
        assert!(gool_mode_applicable("gool"));
        assert!(gool_mode_applicable("gool+psiphon"));
        assert!(gool_mode_applicable("gool+tor"));
        assert!(!gool_mode_applicable("wg"));
        assert!(!gool_mode_applicable("masque"));
        assert!(!gool_mode_applicable("mim"));
        assert!(!gool_mode_applicable("smart"));
        assert!(!gool_mode_applicable("wg+psiphon"));
    }

    #[test]
    fn gool_mode_env_follows_the_core_contract() {
        // v2.3.0 core `gool_classic()`: "wiw" | "wg" | "classic" select classic; the
        // MASQUE-carried default needs no env at all.
        assert_eq!(GoolMode::parse("masque"), GoolMode::Masque);
        assert_eq!(GoolMode::parse("classic"), GoolMode::Classic);
        assert_eq!(GoolMode::parse("wiw"), GoolMode::Classic);
        assert_eq!(GoolMode::parse("anything-else"), GoolMode::Masque);
        assert_eq!(GoolMode::default(), GoolMode::Masque);
        assert_eq!(GoolMode::Masque.env_value(), None);
        assert_eq!(GoolMode::Classic.env_value(), Some("classic"));
    }

    #[test]
    fn scan_mode_labels_track_v2_3_0_upstream_names() {
        // v2.3.0 renamed `stealth` -> `verified`; the stored value stays compatible and the
        // user-facing label reflects the upstream name (dev.032 §2.5).
        assert_eq!(scan_mode_label("stealth"), "Verified");
        assert_eq!(scan_mode_label("turbo"), "Turbo");
        assert_eq!(scan_mode_label("balanced"), "Balanced");
        assert_eq!(scan_mode_label("thorough"), "Thorough");
        assert_eq!(scan_mode_label("ironclad"), "Ironclad");
    }

    #[test]
    fn combined_entries_hidden_in_proxy_mode() {
        assert!(proxy_mode_visible("wg"));
        assert!(proxy_mode_visible("mim"));
        assert!(!proxy_mode_visible("wg+psiphon"));
        assert!(!proxy_mode_visible("masque+tor"));
    }

    #[test]
    fn port_contract_is_stable() {
        assert_eq!(PUBLIC_HTTP, "127.0.0.1:1818");
        assert_eq!(PUBLIC_SOCKS, "127.0.0.1:1819");
        assert_eq!(PSIPHON_SOCKS, "127.0.0.1:1822");
        assert_eq!(PSIPHON_HTTP, "127.0.0.1:1824");
        assert_eq!(TOR_SOCKS, "127.0.0.1:1821");
        assert_eq!(TOR_HTTP, "127.0.0.1:1825");
        // All six ports must be distinct.
        let ports = [
            PUBLIC_HTTP,
            PUBLIC_SOCKS,
            PSIPHON_SOCKS,
            PSIPHON_HTTP,
            TOR_SOCKS,
            TOR_HTTP,
        ];
        for i in 0..ports.len() {
            for j in i + 1..ports.len() {
                assert_ne!(ports[i], ports[j]);
            }
        }
    }

    #[test]
    fn psiphon_transport_parses_and_blocks_cdn() {
        assert_eq!(PsiphonTransport::parse("auto"), Some(PsiphonTransport::Auto));
        assert_eq!(PsiphonTransport::parse("direct"), Some(PsiphonTransport::Direct));
        assert_eq!(PsiphonTransport::parse("cdn"), None);
    }
}
