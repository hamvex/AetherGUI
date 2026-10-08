#![cfg_attr(test, allow(clippy::field_reassign_with_default))]

use crate::protocol::{self, is_known, privacy_chain, PrivacyChain};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, path::Path};

/// Transport encapsulation ceilings for the TUN MTU.
pub const MASQUE_MTU_CAP: u16 = 1400;
pub const WIREGUARD_MTU_CAP: u16 = 1420;
pub const NESTED_WIREGUARD_MTU_CAP: u16 = 1360;
pub const MIN_TUN_MTU: u16 = 1280;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub language: String,
    pub appearance: String,
    pub orb_style: String,
    #[serde(default = "default_automatic_updates")]
    pub automatic_updates: bool,
    pub connection_mode: String,
    pub routing_mode: String,
    pub dns_leak_protection: bool,
    pub ipv6_behavior: String,
    pub kill_switch: bool,
    pub tun_mtu: u16,
    pub split_applications: Vec<String>,
    pub route_exclusions: Vec<String>,
    pub protocol: String,
    pub scan_mode: String,
    pub log_level: String,
    pub ip_mode: String,
    pub obfuscation: String,
    pub masque_transport: String,
    pub socks_address: String,
    pub allow_remote_listener: bool,
    pub peer: String,
    #[serde(default)]
    pub mim_outer_peer: String,
    #[serde(default)]
    pub mim_inner_peer: String,
    #[serde(default = "default_quic_v2")]
    pub quic_v2: bool,
    pub wg_keepalive: u16,
    pub stall_timeout: u64,
    pub watchdog: bool,
    pub config_path: String,
    pub wg_config_path: String,
    pub masque_config_path: String,
    pub quick_reconnect: bool,
    pub auto_connect_at_start: bool,
    pub dns_resolvers: String,
    pub route_block: Vec<String>,
    pub route_direct: Vec<String>,
    pub routes_file: String,
    #[serde(default)]
    pub psiphon_chain_enabled: bool,
    #[serde(default)]
    pub psiphon_region: String,
    #[serde(default)]
    pub psiphon_local_port: u16,
    /// Psiphon transport for +Psiphon protocols: "auto" | "direct" (cdn blocked, Android parity).
    #[serde(default)]
    pub psiphon_transport: String,
    /// Tor privacy chain participation is derived from the Protocol entry (no separate toggle).
    #[serde(default)]
    pub tor_chain_enabled: bool,
    pub ech: String,
    pub h2_fragment: bool,
    pub h2_fragment_size: String,
    pub h2_fragment_delay: String,
    pub no_data_check: bool,
    pub validate_secs: u64,
    pub startup_secs: u64,
    pub reconnect_secs: u64,
    #[serde(default)]
    pub wiw_outer_peer: String,
    #[serde(default)]
    pub wiw_inner_peer: String,
    /// gool topology variant (Core v2.3.0 `AETHER_GOOL_MODE`): "masque" (v2.3.0 default,
    /// WireGuard carried inside MASQUE) or "classic" (v2.1.0-era WireGuard-in-WireGuard).
    /// Old settings.json files without the key deserialize to the v2.3.0 default, so
    /// saved gool users keep the official default path (the connect flow adds a bounded
    /// classic fallback when the MASQUE path cannot scan).
    #[serde(default = "default_gool_mode")]
    pub gool_mode: String,
    #[serde(default)]
    pub team: String,
    #[serde(default)]
    pub access_email: String,
    #[serde(default)]
    pub access_token: String,
    #[serde(default)]
    pub gateway: bool,
    #[serde(default)]
    pub upstream_proxy: String,
    pub wg_no_profile_retry: bool,
    pub route_sniff: bool,
    pub route_sniff_ms: u64,
}

fn default_automatic_updates() -> bool {
    true
}

fn default_quic_v2() -> bool {
    true
}

fn default_gool_mode() -> String {
    protocol::GoolMode::default().as_str().into()
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: "en".into(),
            appearance: "system".into(),
            orb_style: "living-mercury".into(),
            automatic_updates: true,
            connection_mode: "vpn".into(),
            routing_mode: "bypass-local".into(),
            dns_leak_protection: true,
            ipv6_behavior: "tunnel".into(),
            kill_switch: false,
            tun_mtu: 1500,
            split_applications: Vec::new(),
            route_exclusions: Vec::new(),
            // Android dev.020 CHANGE 1: fresh default is WireGuard + Psiphon.
            protocol: protocol::DEFAULT_PROTOCOL.into(),
            scan_mode: "balanced".into(),
            log_level: "info".into(),
            ip_mode: "v4".into(),
            obfuscation: "balanced".into(),
            // Android dev.020 CHANGE 3: HTTP/2 (TCP) is the default MASQUE connection method.
            masque_transport: "h2".into(),
            socks_address: "127.0.0.1:1819".into(),
            allow_remote_listener: false,
            peer: String::new(),
            mim_outer_peer: String::new(),
            mim_inner_peer: String::new(),
            quic_v2: true,
            wg_keepalive: 5,
            stall_timeout: 90,
            watchdog: true,
            config_path: String::new(),
            wg_config_path: String::new(),
            masque_config_path: String::new(),
            quick_reconnect: true,
            auto_connect_at_start: false,
            dns_resolvers: String::new(),
            route_block: Vec::new(),
            route_direct: Vec::new(),
            routes_file: String::new(),
            psiphon_chain_enabled: false,
            psiphon_region: String::new(),
            psiphon_local_port: 0,
            psiphon_transport: "auto".into(),
            tor_chain_enabled: false,
            ech: String::new(),
            h2_fragment: false,
            h2_fragment_size: "16-32".into(),
            h2_fragment_delay: "2-10".into(),
            no_data_check: false,
            validate_secs: 10,
            startup_secs: 30,
            reconnect_secs: 2,
            wiw_outer_peer: String::new(),
            wiw_inner_peer: String::new(),
            gool_mode: protocol::GoolMode::default().as_str().into(),
            team: String::new(),
            access_email: String::new(),
            access_token: String::new(),
            gateway: false,
            upstream_proxy: String::new(),
            wg_no_profile_retry: false,
            route_sniff: true,
            route_sniff_ms: 400,
        }
    }
}

impl Settings {
    /// Largest safe TUN MTU for the selected transport. MASQUE adds QUIC/HTTP datagram framing,
    /// WireGuard adds its own 60-byte header, and `gool` nests WireGuard inside WireGuard, so a
    /// 1500-byte TUN would force every packet to fragment on the way out. Mirrors
    /// `AetherVpnService.effectiveMtu` on Android so both platforms agree.
    pub fn effective_tun_mtu(&self) -> u16 {
        // Cap by the BASE transport (a combined entry like gool+tor still nests WireGuard).
        Self::cap_tun_mtu(protocol::base_protocol(&self.protocol), self.tun_mtu)
    }

    pub fn cap_tun_mtu(protocol: &str, configured: u16) -> u16 {
        let transport_cap = match protocol {
            "gool" | "smart" => NESTED_WIREGUARD_MTU_CAP,
            "wg" => WIREGUARD_MTU_CAP,
            _ => MASQUE_MTU_CAP,
        };
        configured.min(transport_cap).max(MIN_TUN_MTU)
    }

    /// True only when the Aether core actually carries IPv6 upstream. When it does not, IPv6 has
    /// to be rejected rather than forwarded into an IPv4-only tunnel.
    pub fn ipv6_upstream(&self) -> bool {
        self.ipv6_behavior == "tunnel" && matches!(self.ip_mode.as_str(), "v6" | "both")
    }

    /// Migrate protocol-specific values from older Windows UI versions before validation.
    /// Minimal and documented (PROMPT §13): an existing user's saved protocol is never
    /// overwritten; only invalid legacy state is repaired.
    pub fn normalize_protocol_options(&mut self) {
        if !is_known(&self.protocol) {
            if self.protocol == "psiphon" || self.psiphon_chain_enabled {
                // Legacy "psiphon" experiments (never released) repair to the default chain.
                self.protocol = protocol::DEFAULT_PROTOCOL.into();
                self.psiphon_chain_enabled = false;
            } else {
                self.protocol = protocol::DEFAULT_PROTOCOL.into();
            }
        }
        // Chain participation is protocol-owned (Android dev.019): keep the old carrier fields
        // consistent so no stale chain state can ever reject a connect (the dev.018 Android
        // defect class). Writing them here makes every protocol switch atomic.
        match privacy_chain(&self.protocol) {
            PrivacyChain::Psiphon => {
                self.psiphon_chain_enabled = true;
                self.tor_chain_enabled = false;
            }
            PrivacyChain::Tor => {
                self.psiphon_chain_enabled = false;
                self.tor_chain_enabled = true;
            }
            PrivacyChain::None => {
                self.psiphon_chain_enabled = false;
                self.tor_chain_enabled = false;
            }
        }
        if !["auto", "direct"].contains(&self.psiphon_transport.as_str()) {
            self.psiphon_transport = "auto".into();
        }
        // gool mode: only the two official v2.3.0 spellings are meaningful; anything else
        // (including a missing key from an old settings.json) repairs to the v2.3.0 default.
        self.gool_mode = protocol::GoolMode::parse(&self.gool_mode).as_str().into();
        if !["firewall", "gfw", "balanced", "aggressive", "off"]
            .contains(&self.obfuscation.as_str())
        {
            self.obfuscation = "balanced".into();
        }
        if !["h3", "h2"].contains(&self.masque_transport.as_str()) {
            self.masque_transport = "h2".into();
        }
        if !["auto", ""].contains(&self.ech.as_str()) && !self.ech.trim().is_empty() {
            // Validate base64 ECH config if provided
            use base64::Engine;
            if base64::engine::general_purpose::STANDARD.decode(&self.ech).is_err() {
                self.ech = String::new();
            }
        }
        if self.h2_fragment_size.trim().is_empty() {
            self.h2_fragment_size = "16-32".into();
        }
        if self.h2_fragment_delay.trim().is_empty() {
            self.h2_fragment_delay = "2-10".into();
        }
        if self.validate_secs == 0 {
            self.validate_secs = 10;
        }
        if self.startup_secs == 0 {
            self.startup_secs = 30;
        }
        if self.reconnect_secs == 0 {
            self.reconnect_secs = 2;
        }
        if self.route_sniff_ms == 0 {
            self.route_sniff_ms = 400;
        }
    }

    pub fn validate(&self) -> Result<(), String> {
        one_of("connection mode", &self.connection_mode, &["vpn", "manual"])?;
        one_of("orb style", &self.orb_style, &["classic", "living-mercury"])?;
        // Psiphon region flows into the core's environment: strict ISO 3166-1 alpha-2 (or
        // empty = Automatic). Anything else would be env-var injection surface.
        if !self.psiphon_region.trim().is_empty() {
            let region = self.psiphon_region.trim().to_ascii_uppercase();
            if !(region.len() == 2 && region.chars().all(|c| c.is_ascii_alphabetic())) {
                return Err("Psiphon region must be a two-letter country code or Automatic".into());
            }
        }
        one_of(
            "routing mode",
            &self.routing_mode,
            &["full", "bypass-local", "split-include", "split-exclude"],
        )?;
        one_of("IPv6 behavior", &self.ipv6_behavior, &["tunnel", "block"])?;
        if !(1280..=9000).contains(&self.tun_mtu) {
            return Err("TUN MTU must be between 1280 and 9000".into());
        }
        for path in &self.split_applications {
            let path = Path::new(path);
            if !path.is_absolute()
                || path
                    .extension()
                    .and_then(|v| v.to_str())
                    .map(|v| !v.eq_ignore_ascii_case("exe"))
                    .unwrap_or(true)
            {
                return Err("Split-tunnel applications must be absolute .exe paths".into());
            }
        }
        for cidr in &self.route_exclusions {
            if cidr.contains(['\0', ' ', ';', '&', '|']) || !cidr.contains('/') {
                return Err("Route exclusions must be CIDR addresses".into());
            }
        }
        // Combined protocol entries are chain-mode (full-device) features: Proxy/manual mode
        // hides them in the UI but a saved combined protocol must still validate (Android
        // dev.017 preserved saved prefs while filtering the dropdown).
        if !is_known(&self.protocol) {
            return Err(format!("Unsupported protocol: {}", self.protocol));
        }
        // Active-conflict guards only (Android dev.019 fix): residual carrier fields of a
        // deactivated chain can never block an unrelated protocol.
        if privacy_chain(&self.protocol) == PrivacyChain::Psiphon
            && !self.upstream_proxy.trim().is_empty()
        {
            return Err("Psiphon cannot be combined with an upstream proxy".into());
        }
        if privacy_chain(&self.protocol) == PrivacyChain::Tor
            && !self.upstream_proxy.trim().is_empty()
        {
            return Err("Tor cannot be combined with an upstream proxy".into());
        }
        one_of(
            "scan mode",
            &self.scan_mode,
            &["turbo", "balanced", "thorough", "stealth", "ironclad"],
        )?;
        one_of(
            "log level",
            &self.log_level,
            &["error", "warn", "info", "debug", "trace"],
        )?;
        one_of("IP mode", &self.ip_mode, &["v4", "v6", "both"])?;
        one_of("MASQUE transport", &self.masque_transport, &["h3", "h2"])?;
        one_of(
            "obfuscation profile",
            &self.obfuscation,
            &["firewall", "gfw", "balanced", "aggressive", "off"],
        )?;
        let listen: SocketAddr = self.socks_address.parse().map_err(|_| {
            "SOCKS5 address must be an IP address and port, for example 127.0.0.1:1819".to_string()
        })?;
        if !listen.ip().is_loopback() && !self.allow_remote_listener {
            return Err(
                "A non-local SOCKS5 listener requires explicit risk acknowledgement".into(),
            );
        }
        if !self.peer.trim().is_empty() {
            self.peer
                .trim()
                .parse::<SocketAddr>()
                .map_err(|_| "Custom endpoint must be an IP address and port".to_string())?;
        }
        for (label, value) in [
            ("MASQUE-in-MASQUE outer endpoint", &self.mim_outer_peer),
            ("MASQUE-in-MASQUE inner endpoint", &self.mim_inner_peer),
        ] {
            if !value.trim().is_empty() {
                value
                    .trim()
                    .parse::<SocketAddr>()
                    .map_err(|_| format!("{label} must be an IP address and port"))?;
            }
        }
        if base_mim(self) && !self.mim_outer_peer.trim().is_empty()
            && !self.mim_inner_peer.trim().is_empty()
            && self.mim_outer_peer.trim() == self.mim_inner_peer.trim()
        {
            return Err("MASQUE-in-MASQUE endpoints must be different".into());
        }
        if base_gool(self) && !self.wiw_outer_peer.trim().is_empty()
            && !self.wiw_inner_peer.trim().is_empty()
            && self.wiw_outer_peer.trim() == self.wiw_inner_peer.trim()
        {
            return Err("WARP-in-WARP endpoints must be different".into());
        }
        if !(1..=65535).contains(&self.wg_keepalive) {
            return Err("WireGuard keepalive must be between 1 and 65535 seconds".into());
        }
        if !(10..=3600).contains(&self.stall_timeout) {
            return Err("Stall timeout must be between 10 and 3600 seconds".into());
        }
        for (label, value) in [
            ("configuration", &self.config_path),
            ("WireGuard configuration", &self.wg_config_path),
            ("MASQUE configuration", &self.masque_config_path),
            ("routing rules", &self.routes_file),
        ] {
            if !value.trim().is_empty()
                && (Path::new(value).file_name().is_none() || value.contains('\0'))
            {
                return Err(format!("Invalid {label} file path"));
            }
        }
        if self.dns_resolvers.split(',').any(|resolver| {
            let value = resolver.trim();
            !value.is_empty() && value.parse::<std::net::IpAddr>().is_err()
        }) {
            return Err("DNS resolvers must be comma-separated IP addresses".into());
        }
        for (label, rules) in [
            ("blocked", &self.route_block),
            ("direct", &self.route_direct),
        ] {
            if rules
                .iter()
                .any(|rule| rule.trim().is_empty() || rule.contains(['\0', ';', '&', '|']))
            {
                return Err(format!("Invalid {label} routing rule"));
            }
        }
        // ECH validation
        if !self.ech.trim().is_empty() && self.ech != "auto" {
            use base64::Engine;
            if base64::engine::general_purpose::STANDARD.decode(&self.ech).is_err() {
                return Err("ECH must be 'auto' or a valid base64-encoded config".into());
            }
        }
        // H2 fragment size/delay validation
        if self.h2_fragment {
            for (label, value, pattern) in [
                ("H2 fragment size", &self.h2_fragment_size, r"^(\d+|\d+-\d+)$"),
                ("H2 fragment delay", &self.h2_fragment_delay, r"^(\d+|\d+-\d+)$"),
            ] {
                if !regex::Regex::new(pattern).unwrap().is_match(value.trim()) {
                    return Err(format!("{label} must be a number or range (e.g., 16-32)"));
                }
            }
        }
        // Timeout validations
        if !(1..=300).contains(&self.validate_secs) {
            return Err("Validate seconds must be between 1 and 300".into());
        }
        if !(5..=300).contains(&self.startup_secs) {
            return Err("Startup seconds must be between 5 and 300".into());
        }
        if !(1..=3600).contains(&self.reconnect_secs) {
            return Err("Reconnect seconds must be between 1 and 3600".into());
        }
        // WARP-in-WARP endpoint validation
        for (label, value) in [
            ("WARP-in-WARP outer endpoint", &self.wiw_outer_peer),
            ("WARP-in-WARP inner endpoint", &self.wiw_inner_peer),
        ] {
            if !value.trim().is_empty() {
                value
                    .trim()
                    .parse::<SocketAddr>()
                    .map_err(|_| format!("{label} must be an IP address and port"))?;
            }
        }
        if base_gool(self)
            && !self.wiw_outer_peer.trim().is_empty()
            && !self.wiw_inner_peer.trim().is_empty()
            && self.wiw_outer_peer.trim() == self.wiw_inner_peer.trim()
        {
            return Err("WARP-in-WARP endpoints must be different".into());
        }
        // Team/Zero Trust validation
        if !self.team.trim().is_empty() {
            if self.team.contains(['\0', ' ', ';', '&', '|', '@']) {
                return Err("Team name contains invalid characters".into());
            }
        }
        if !self.access_email.trim().is_empty() && !self.access_email.contains('@') {
            return Err("Access email must be a valid email address".into());
        }
        // Upstream proxy validation
        if !self.upstream_proxy.trim().is_empty() {
            let up = self.upstream_proxy.trim();
            if !(up.starts_with("socks5://") || up.starts_with("http://") || up.starts_with("https://")) {
                return Err("Upstream proxy must start with socks5://, http://, or https://".into());
            }
            if up.starts_with("https://") && up.contains('@') {
                return Err("HTTPS upstream proxy with credentials is not supported".into());
            }
        }
        // Route sniff validation
        if !(10..=5000).contains(&self.route_sniff_ms) {
            return Err("Route sniff timeout must be between 10 and 5000 ms".into());
        }
        // Psiphon settings remain deserializable for backward compatibility, but the
        // suspended production build deliberately ignores them and never starts Psiphon.
        Ok(())
    }

    pub fn environment(&self, default_config: &Path) -> Result<HashMap<String, String>, String> {
        self.environment_with_chain(default_config, None)
    }

    /// Environment assembly. `chain` layers the privacy-chain variables (ports, underlay
    /// remap, helper dirs) on top of the base set; the ProcessManager calls this with the
    /// resolved ChainRuntime so every spawn site shares one mapping.
    pub fn environment_with_chain(
        &self,
        default_config: &Path,
        chain: Option<&crate::chain::ChainRuntime>,
    ) -> Result<HashMap<String, String>, String> {
        self.validate()?;
        let base = protocol::base_protocol(&self.protocol);
        let mut env = HashMap::from([
            // The core runs the BASE protocol; the stored value's chain is layered separately.
            ("AETHER_PROTOCOL".into(), base.into()),
            ("AETHER_SCAN".into(), self.scan_mode.clone()),
            ("AETHER_LOG_LEVEL".into(), self.log_level.clone()),
            ("AETHER_IP".into(), self.ip_mode.clone()),
            ("AETHER_NOIZE".into(), self.obfuscation.clone()),
            // Plain protocols: the core owns the public contract directly — SOCKS 1819
            // (AETHER_SOCKS below) AND the HTTP 1818 proxy listener. dev.033 fix:
            // AETHER_HTTP_PROXY was previously only emitted through a ChainRuntime
            // (its None arm), but plain protocols never construct one, so plain
            // connects silently served 1819 only and left the public HTTP 1818 dead.
            // The base env now pins the plain-mode HTTP contract; chain protocols
            // omit the key entirely (the GUI relay owns 1818 there — the core must
            // not bind it, which the existing chain-env tests assert).
            ("AETHER_SOCKS".into(), self.socks_address.clone()),
            (
                "AETHER_QUICK_RECONNECT".into(),
                if self.quick_reconnect { "1" } else { "0" }.into(),
            ),
            (
                "AETHER_CONFIG".into(),
                if self.config_path.trim().is_empty() {
                    default_config.to_string_lossy().into_owned()
                } else {
                    self.config_path.clone()
                },
            ),
        ]);
        // The plain-mode public HTTP contract (see the comment above).
        if protocol::privacy_chain(&self.protocol) == protocol::PrivacyChain::None {
            env.insert("AETHER_HTTP_PROXY".into(), protocol::PUBLIC_HTTP.into());
        }
        if let Some(runtime) = chain {
            for (key, value) in runtime.environment() {
                env.insert(key, value);
            }
        }
        if !self.peer.trim().is_empty() {
            env.insert(
                if base == "masque" {
                    "AETHER_PEER".into()
                } else {
                    "AETHER_WG_PEER".into()
                },
                self.peer.trim().into(),
            );
        }
        if matches!(base, "masque" | "mim") {
            env.insert(
                "AETHER_MASQUE_HTTP2".into(),
                if self.masque_transport == "h2" {
                    "1"
                } else {
                    "0"
                }
                .into(),
            );
            env.insert(
                "AETHER_QUIC_V2".into(),
                if self.quic_v2 { "1" } else { "0" }.into(),
            );
        } else {
            env.insert("AETHER_WG_KEEPALIVE".into(), self.wg_keepalive.to_string());
        }
        if !self.wg_config_path.trim().is_empty() {
            env.insert("AETHER_WG_CONFIG".into(), self.wg_config_path.clone());
        }
        if !self.masque_config_path.trim().is_empty() {
            env.insert(
                "AETHER_MASQUE_CONFIG".into(),
                self.masque_config_path.clone(),
            );
        }
        if base == "mim" {
            if !self.mim_outer_peer.trim().is_empty() {
                env.insert(
                    "AETHER_MIM_OUTER_PEER".into(),
                    self.mim_outer_peer.trim().into(),
                );
            }
            if !self.mim_inner_peer.trim().is_empty() {
                env.insert(
                    "AETHER_MIM_INNER_PEER".into(),
                    self.mim_inner_peer.trim().into(),
                );
            }
        }
        if !self.dns_resolvers.trim().is_empty() {
            env.insert("AETHER_DNS".into(), self.dns_resolvers.trim().into());
        }
        if !self.route_block.is_empty() {
            env.insert("AETHER_ROUTE_BLOCK".into(), self.route_block.join(","));
        }
        if !self.route_direct.is_empty() {
            env.insert("AETHER_ROUTE_DIRECT".into(), self.route_direct.join(","));
        }
        if !self.routes_file.trim().is_empty() {
            env.insert("AETHER_ROUTES_FILE".into(), self.routes_file.clone());
        }
        // ECH (Encrypted Client Hello)
        if !self.ech.trim().is_empty() {
            env.insert("AETHER_ECH".into(), self.ech.trim().into());
        }
        // MASQUE HTTP/2 fragmentation
        if self.h2_fragment {
            env.insert("AETHER_MASQUE_H2_FRAGMENT".into(), "1".into());
            env.insert("AETHER_MASQUE_H2_FRAGMENT_SIZE".into(), self.h2_fragment_size.trim().into());
            env.insert("AETHER_MASQUE_H2_FRAGMENT_DELAY".into(), self.h2_fragment_delay.trim().into());
        }
        // No data check
        if self.no_data_check {
            env.insert("AETHER_MASQUE_NO_DATA_CHECK".into(), "1".into());
            env.insert("AETHER_WG_NO_DATA_CHECK".into(), "1".into());
        }
        // Timeout controls
        env.insert("AETHER_MASQUE_VALIDATE_SECS".into(), self.validate_secs.to_string());
        env.insert("AETHER_WG_VALIDATE_SECS".into(), self.validate_secs.to_string());
        env.insert("AETHER_MASQUE_STARTUP_SECS".into(), self.startup_secs.to_string());
        env.insert("AETHER_MASQUE_RECONNECT_SECS".into(), self.reconnect_secs.to_string());
        env.insert("AETHER_WG_RECONNECT_SECS".into(), self.reconnect_secs.to_string());
        // WARP-in-WARP custom endpoints
        if base == "gool" {
            if !self.wiw_outer_peer.trim().is_empty() {
                env.insert("AETHER_WIW_OUTER_PEER".into(), self.wiw_outer_peer.trim().into());
            }
            if !self.wiw_inner_peer.trim().is_empty() {
                env.insert("AETHER_WIW_INNER_PEER".into(), self.wiw_inner_peer.trim().into());
            }
            // gool topology (v2.3.0 `AETHER_GOOL_MODE`). Classic maps to the core's
            // `--gool-classic` spelling; the MASQUE-carried default needs no env (the core
            // default is exactly that). Custom WiW endpoints already force classic in-core,
            // so an explicit masque mode with endpoints set is respected upstream, not
            // contradicted here.
            if let Some(value) = protocol::GoolMode::parse(&self.gool_mode).env_value() {
                env.insert("AETHER_GOOL_MODE".into(), value.into());
            }
        }
        // Zero Trust / Team
        if !self.team.trim().is_empty() {
            env.insert("AETHER_TEAM".into(), self.team.trim().into());
        }
        if !self.access_email.trim().is_empty() {
            env.insert("AETHER_ACCESS_EMAIL".into(), self.access_email.trim().into());
        }
        if !self.access_token.trim().is_empty() {
            env.insert("AETHER_ACCESS_TOKEN".into(), self.access_token.trim().into());
        }
        if self.gateway {
            env.insert("AETHER_GATEWAY".into(), "1".into());
        }
        // Upstream proxy
        if !self.upstream_proxy.trim().is_empty() {
            env.insert("AETHER_UPSTREAM".into(), self.upstream_proxy.trim().into());
        }
        // WireGuard no profile retry
        if self.wg_no_profile_retry {
            env.insert("AETHER_WG_NO_PROFILE_RETRY".into(), "1".into());
        }
        // Route sniff
        if !self.route_sniff {
            env.insert("AETHER_ROUTE_SNIFF".into(), "0".into());
        }
        env.insert("AETHER_ROUTE_SNIFF_MS".into(), self.route_sniff_ms.to_string());
        Ok(env)
    }
}

fn one_of(label: &str, value: &str, options: &[&str]) -> Result<(), String> {
    options
        .contains(&value)
        .then_some(())
        .ok_or_else(|| format!("Unsupported {label}: {value}"))
}

/// Base-protocol checks used by validate(): the combined entries validate exactly like their
/// base (Android dev.018 ProtocolOrder.baseIndex fix — clamping combined indexes to plain
/// bases broke Tor chains; never repeat that).
fn base_mim(settings: &Settings) -> bool {
    protocol::base_protocol(&settings.protocol) == "mim"
}

fn base_gool(settings: &Settings) -> bool {
    protocol::base_protocol(&settings.protocol) == "gool"
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_map_to_documented_environment() {
        let settings = Settings::default();
        assert_eq!(settings.scan_mode, "balanced");
        assert_eq!(settings.obfuscation, "balanced");
        // Android dev.020 defaults: WireGuard + Psiphon, HTTP/2 transport.
        assert_eq!(settings.protocol, "wg+psiphon");
        assert_eq!(settings.masque_transport, "h2");
        assert_eq!(settings.psiphon_transport, "auto");
        let env = settings
            .environment(Path::new("C:/data/aether.toml"))
            .unwrap();
        // The core receives the BASE protocol; the chain is layered by ChainRuntime.
        assert_eq!(env["AETHER_PROTOCOL"], "wg");
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:1819");
        // Default protocol is a CHAIN (wg+psiphon): the core must not serve 1818
        // (the GUI relay owns it); the plain HTTP contract is asserted below.
        assert!(!env.contains_key("AETHER_HTTP_PROXY"));
    }

    #[test]
    fn plain_protocols_pin_the_public_http_contract_on_the_core() {
        // dev.033: plain protocols previously never emitted AETHER_HTTP_PROXY — the
        // env key existed only inside ChainRuntime::environment() (its None arm), and
        // plain connects construct no runtime, so 1818 stayed dead while 1819 worked
        // (physically measured: gool connected with real SOCKS 1819 traffic while
        // HTTP 1818 refused every request). The public contract requires BOTH ports
        // in plain mode, served by the core.
        for plain in ["wg", "gool", "masque", "mim", "smart"] {
            let mut settings = Settings::default();
            settings.protocol = plain.into();
            settings.normalize_protocol_options();
            let env = settings
                .environment(Path::new("C:/data/aether.toml"))
                .unwrap();
            assert_eq!(env["AETHER_HTTP_PROXY"], "127.0.0.1:1818", "{plain}");
            assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:1819", "{plain}");
        }
        // Chain protocols still omit the key (the GUI relay owns 1818 there).
        for combined in ["wg+psiphon", "gool+psiphon", "masque+psiphon", "wg+tor", "gool+tor", "masque+tor"] {
            let mut settings = Settings::default();
            settings.protocol = combined.into();
            settings.normalize_protocol_options();
            let env = settings
                .environment(Path::new("C:/data/aether.toml"))
                .unwrap();
            assert!(!env.contains_key("AETHER_HTTP_PROXY"), "{combined}");
        }
    }

    #[test]
    fn chain_runtime_remaps_the_underlay_and_enables_psiphon() {
        let settings = Settings::default(); // wg+psiphon
        let runtime = crate::chain::ChainRuntime {
            chain: crate::protocol::PrivacyChain::Psiphon,
            internal_socks: "127.0.0.1:18193".into(),
            psiphon_transport: settings.psiphon_transport.clone(),
            psiphon_region: settings.psiphon_region.clone(),
            tor_dir: String::new(),
        };
        let env = settings
            .environment_with_chain(Path::new("C:/data/aether.toml"), Some(&runtime))
            .unwrap();
        assert_eq!(env["AETHER_PROTOCOL"], "wg");
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:18193");
        assert_eq!(env["AETHER_PSIPHON"], "chain");
        assert_eq!(env["AETHER_PSIPHON_BIND"], "127.0.0.1:1822");
        assert_eq!(env["AETHER_PSIPHON_HTTP"], "127.0.0.1:1824");
        assert_eq!(env["AETHER_PSIPHON_MODE"], "auto");
        assert!(!env.contains_key("AETHER_HTTP_PROXY"));
    }

    #[test]
    fn tor_chain_runtime_pins_the_android_topology() {
        let mut settings = Settings::default();
        settings.protocol = "masque+tor".into();
        settings.normalize_protocol_options();
        let runtime = crate::chain::ChainRuntime {
            chain: crate::protocol::PrivacyChain::Tor,
            internal_socks: "127.0.0.1:18193".into(),
            psiphon_transport: String::new(),
            psiphon_region: String::new(),
            tor_dir: "C:/data/aether-tor".into(),
        };
        let env = settings
            .environment_with_chain(Path::new("C:/data/aether.toml"), Some(&runtime))
            .unwrap();
        assert_eq!(env["AETHER_PROTOCOL"], "masque");
        assert_eq!(env["AETHER_TOR"], "chain");
        assert_eq!(env["AETHER_TOR_BIND"], "127.0.0.1:1821");
        assert_eq!(env["AETHER_TOR_HTTP"], "127.0.0.1:1825");
        assert_eq!(env["AETHER_TOR_BRIDGES"], "off");
        assert_eq!(env["AETHER_MASQUE_HTTP2"], "1"); // h2 default applies to masque base
    }

    #[test]
    fn protocol_switching_is_atomic_and_never_leaves_stale_chain_state() {
        // The dev.018 Android defect class: +Tor -> +Psiphon left torProxy=true and every
        // connect was rejected. Windows must apply the complete chain state atomically.
        let mut settings = Settings::default();
        for sequence in [
            ["wg+tor", "wg+psiphon"],
            ["gool+tor", "gool+psiphon"],
            ["masque+tor", "masque+psiphon"],
            ["masque+psiphon", "masque+tor"],
            ["wg+psiphon", "wg"],
            ["gool+tor", "gool"],
            ["smart", "smart"],
        ] {
            settings.protocol = sequence[0].into();
            settings.normalize_protocol_options();
            assert!(settings.validate().is_ok(), "{}", sequence[0]);
            settings.protocol = sequence[1].into();
            settings.normalize_protocol_options();
            assert!(settings.validate().is_ok(), "{}", sequence[1]);
            let chain = privacy_chain(&settings.protocol);
            assert_eq!(
                settings.psiphon_chain_enabled,
                chain == PrivacyChain::Psiphon,
                "{}",
                sequence[1]
            );
            assert_eq!(
                settings.tor_chain_enabled,
                chain == PrivacyChain::Tor,
                "{}",
                sequence[1]
            );
        }
    }

    #[test]
    fn legacy_invalid_protocol_repairs_to_the_new_default() {
        let mut settings = Settings::default();
        settings.protocol = "psiphon".into();
        settings.normalize_protocol_options();
        assert_eq!(settings.protocol, "wg+psiphon");
        settings.protocol = "nonsense".into();
        settings.normalize_protocol_options();
        assert_eq!(settings.protocol, "wg+psiphon");
    }

    #[test]
    fn existing_user_protocol_survives_normalization() {
        // PROMPT §13: never overwrite an existing user's saved protocol during upgrade.
        let mut settings = Settings::default();
        settings.protocol = "gool".into();
        settings.normalize_protocol_options();
        assert_eq!(settings.protocol, "gool");
        assert!(!settings.psiphon_chain_enabled);
        assert!(!settings.tor_chain_enabled);
    }

    #[test]
    fn chain_conflicts_with_upstream_proxy_are_active_only() {
        let mut settings = Settings::default();
        settings.protocol = "wg".into();
        settings.upstream_proxy = "socks5://127.0.0.1:1080".into();
        settings.normalize_protocol_options();
        assert!(settings.validate().is_ok()); // plain protocol + upstream is fine
        settings.protocol = "wg+psiphon".into();
        settings.normalize_protocol_options();
        assert!(settings.validate().is_err()); // psiphon + upstream rejected
        settings.protocol = "masque+tor".into();
        settings.normalize_protocol_options();
        assert!(settings.validate().is_err()); // tor + upstream rejected
    }

    #[test]
    fn saved_scan_and_obfuscation_values_override_new_user_defaults() {
        let saved: Settings =
            serde_json::from_str(r#"{"scanMode":"thorough","obfuscation":"firewall"}"#).unwrap();
        assert_eq!(saved.scan_mode, "thorough");
        assert_eq!(saved.obfuscation, "firewall");
    }
    #[test]
    fn android_obfuscation_profiles_are_supported_for_all_protocols() {
        let mut s = Settings::default();
        s.protocol = "wg".into();
        assert!(s.validate().is_ok());
        s.obfuscation = "firewall".into();
        assert!(s.validate().is_ok());
        for profile in ["gfw", "balanced", "aggressive", "off"] {
            s.obfuscation = profile.into();
            assert!(s.validate().is_ok(), "profile {profile} should be accepted");
        }
    }
    #[test]
    fn masque_legacy_obfuscation_is_migrated_without_touching_scan_mode() {
        let mut settings = Settings::default();
        settings.protocol = "masque".into();
        settings.obfuscation = "balanced".into();
        settings.scan_mode = "ironclad".into();
        settings.masque_transport = "invalid".into();
        settings.normalize_protocol_options();
        assert_eq!(settings.obfuscation, "balanced");
        assert_eq!(settings.masque_transport, "h2");
        assert_eq!(settings.scan_mode, "ironclad");
        assert!(settings.validate().is_ok());
    }
    #[test]
    fn tun_mtu_never_exceeds_transport_encapsulation_limits() {
        let mut settings = Settings::default();
        settings.tun_mtu = 1500;
        settings.protocol = "gool".into();
        assert_eq!(settings.effective_tun_mtu(), 1360);
        settings.protocol = "wg".into();
        assert_eq!(settings.effective_tun_mtu(), 1420);
        settings.protocol = "masque".into();
        assert_eq!(settings.effective_tun_mtu(), 1400);
        // A safe user value is preserved, and the IPv6 minimum is still the floor.
        settings.tun_mtu = 1320;
        assert_eq!(settings.effective_tun_mtu(), 1320);
        settings.tun_mtu = 1280;
        settings.protocol = "gool".into();
        assert_eq!(settings.effective_tun_mtu(), 1280);
        assert_eq!(Settings::cap_tun_mtu("gool", 1281), 1281);
    }

    #[test]
    fn ipv6_upstream_requires_both_tunnelling_and_an_ipv6_core() {
        let mut settings = Settings::default();
        settings.ipv6_behavior = "tunnel".into();
        settings.ip_mode = "v4".into();
        assert!(!settings.ipv6_upstream());
        settings.ip_mode = "v6".into();
        assert!(settings.ipv6_upstream());
        settings.ip_mode = "both".into();
        assert!(settings.ipv6_upstream());
        settings.ipv6_behavior = "block".into();
        assert!(!settings.ipv6_upstream());
    }

    #[test]
    fn non_loopback_listener_is_rejected() {
        let mut s = Settings::default();
        s.socks_address = "0.0.0.0:1819".into();
        assert!(s.validate().unwrap_err().contains("acknowledgement"));
        s.allow_remote_listener = true;
        assert!(s.validate().is_ok());
    }
    #[test]
    fn invalid_values_are_rejected() {
        let mut s = Settings::default();
        s.peer = "example.com:443".into();
        assert!(s.validate().is_err());
        s.peer.clear();
        s.socks_address = "127.0.0.1:70000".into();
        assert!(s.validate().is_err());
    }
    #[test]
    fn settings_round_trip_without_secrets() {
        let settings = Settings::default();
        let json = serde_json::to_string(&settings).unwrap();
        assert_eq!(serde_json::from_str::<Settings>(&json).unwrap(), settings);
        assert!(!json.contains("private_key"));
    }

    #[test]
    fn update_preference_is_persisted_but_not_forwarded_to_core() {
        let mut settings = Settings::default();
        settings.automatic_updates = false;
        let json = serde_json::to_string(&settings).unwrap();
        assert!(json.contains("\"automaticUpdates\":false"));
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert!(!env.contains_key("AETHER_AUTOMATIC_UPDATES"));
    }

    #[test]
    fn wireguard_and_h2_use_exact_core_names() {
        let mut settings = Settings::default();
        settings.protocol = "masque".into();
        settings.obfuscation = "firewall".into();
        settings.masque_transport = "h2".into();
        let h2 = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(h2["AETHER_MASQUE_HTTP2"], "1");
        settings.protocol = "wg".into();
        settings.obfuscation = "balanced".into();
        settings.peer = "162.159.192.1:2408".into();
        let wg = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(wg["AETHER_WG_PEER"], "162.159.192.1:2408");
        assert_eq!(wg["AETHER_WG_KEEPALIVE"], "5");
    }

    #[test]
    fn core_v2_feature_settings_map_to_exact_environment_variables() {
        let mut settings = Settings::default();
        // Plain gool so WIW peers emit AND upstream proxy stays valid (chains reject upstream).
        settings.protocol = "gool".into();
        settings.normalize_protocol_options();
        settings.ech = "YWJjZA==".into();
        settings.h2_fragment = true;
        settings.h2_fragment_size = "24-48".into();
        settings.h2_fragment_delay = "3-9".into();
        settings.no_data_check = true;
        settings.validate_secs = 17;
        settings.startup_secs = 42;
        settings.reconnect_secs = 7;
        settings.wiw_outer_peer = "162.159.192.1:2408".into();
        settings.wiw_inner_peer = "188.114.96.1:2408".into();
        settings.team = "acme".into();
        settings.access_email = "me@example.com".into();
        settings.access_token = "enrolment-token".into();
        settings.gateway = true;
        settings.upstream_proxy = "socks5://127.0.0.1:1080".into();
        settings.wg_no_profile_retry = true;
        settings.route_sniff = false;
        settings.route_sniff_ms = 777;

        let env = settings.environment(Path::new("aether.toml")).unwrap();
        for (key, expected) in [
            ("AETHER_ECH", "YWJjZA=="),
            ("AETHER_MASQUE_H2_FRAGMENT", "1"),
            ("AETHER_MASQUE_H2_FRAGMENT_SIZE", "24-48"),
            ("AETHER_MASQUE_H2_FRAGMENT_DELAY", "3-9"),
            ("AETHER_MASQUE_NO_DATA_CHECK", "1"),
            ("AETHER_WG_NO_DATA_CHECK", "1"),
            ("AETHER_MASQUE_VALIDATE_SECS", "17"),
            ("AETHER_WG_VALIDATE_SECS", "17"),
            ("AETHER_MASQUE_STARTUP_SECS", "42"),
            ("AETHER_MASQUE_RECONNECT_SECS", "7"),
            ("AETHER_WG_RECONNECT_SECS", "7"),
            ("AETHER_WIW_OUTER_PEER", "162.159.192.1:2408"),
            ("AETHER_WIW_INNER_PEER", "188.114.96.1:2408"),
            ("AETHER_TEAM", "acme"),
            ("AETHER_ACCESS_EMAIL", "me@example.com"),
            ("AETHER_ACCESS_TOKEN", "enrolment-token"),
            ("AETHER_GATEWAY", "1"),
            ("AETHER_UPSTREAM", "socks5://127.0.0.1:1080"),
            ("AETHER_WG_NO_PROFILE_RETRY", "1"),
            ("AETHER_ROUTE_SNIFF", "0"),
            ("AETHER_ROUTE_SNIFF_MS", "777"),
        ] {
            assert_eq!(env.get(key).map(String::as_str), Some(expected), "{key}");
        }
    }

    #[test]
    fn mim_settings_map_mim_endpoints_and_quic_v2() {
        let mut settings = Settings::default();
        settings.protocol = "mim".into();
        settings.masque_transport = "h2".into();
        settings.quic_v2 = false;
        settings.mim_outer_peer = "162.159.192.1:2408".into();
        settings.mim_inner_peer = "188.114.96.1:2408".into();
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(env["AETHER_MASQUE_HTTP2"], "1");
        assert_eq!(env["AETHER_QUIC_V2"], "0");
        assert_eq!(env["AETHER_MIM_OUTER_PEER"], "162.159.192.1:2408");
        assert_eq!(env["AETHER_MIM_INNER_PEER"], "188.114.96.1:2408");
    }

    #[test]
    fn invalid_core_v2_values_are_rejected_and_old_settings_get_defaults() {
        let mut settings = Settings::default();
        settings.ech = "not base64 %%".into();
        assert!(settings.validate().unwrap_err().contains("ECH"));
        settings.ech = "YWJj".into();
        settings.h2_fragment = true;
        settings.h2_fragment_size = "bad range".into();
        assert!(settings.validate().unwrap_err().contains("fragment size"));

        let old: Settings = serde_json::from_str(
            r#"{"protocol":"gool","scanMode":"balanced","socksAddress":"127.0.0.1:1819"}"#,
        )
        .unwrap();
        assert_eq!(old.ech, "");
        assert!(!old.h2_fragment);
        assert_eq!(old.validate_secs, 10);
        assert_eq!(old.startup_secs, 30);
        assert_eq!(old.reconnect_secs, 2);
        assert!(old.route_sniff);
        assert_eq!(old.route_sniff_ms, 400);
    }

    #[test]
    fn gool_mode_maps_to_the_v2_3_0_env_contract() {
        // Default (masque-carried gool): no AETHER_GOOL_MODE at all — the core's own default.
        let mut settings = Settings::default();
        settings.protocol = "gool+psiphon".into();
        settings.normalize_protocol_options();
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert!(!env.contains_key("AETHER_GOOL_MODE"));

        // Classic: exactly the core's --gool-classic spelling.
        settings.gool_mode = "classic".into();
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(env["AETHER_GOOL_MODE"], "classic");

        // Non-gool bases never emit it.
        settings.protocol = "wg".into();
        settings.gool_mode = "classic".into();
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert!(!env.contains_key("AETHER_GOOL_MODE"));
    }

    #[test]
    fn old_settings_files_get_the_v2_3_0_default_gool_mode_and_migrate_spellings() {
        // An old settings.json (pre-dev.032) has no goolMode key: deserialization yields
        // the default, and normalization accepts the core's alternative classic spellings.
        let old: Settings =
            serde_json::from_str(r#"{"protocol":"gool","goolMode":"wiw"}"#).unwrap();
        assert_eq!(old.gool_mode, "wiw");
        let mut normalized = old;
        normalized.normalize_protocol_options();
        assert_eq!(normalized.gool_mode, "classic");
        let env = normalized.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(env["AETHER_GOOL_MODE"], "classic");

        let missing: Settings =
            serde_json::from_str(r#"{"protocol":"gool"}"#).unwrap();
        assert_eq!(missing.gool_mode, "masque");

        let junk: Settings = serde_json::from_str(r#"{"protocol":"gool","goolMode":"junk"}"#).unwrap();
        let mut normalized = junk;
        normalized.normalize_protocol_options();
        assert_eq!(normalized.gool_mode, "masque");
    }

    #[test]
    fn saved_gool_mode_survives_a_protocol_switch_and_never_rejects_a_connect() {
        // The stored gool mode is advisory state tied to the gool family: switching away
        // and back must preserve it, and it must never block validation of any protocol.
        let mut settings = Settings::default();
        settings.gool_mode = "classic".into();
        for sequence in [
            ["gool", "wg", "gool"],
            ["gool+psiphon", "masque", "gool+psiphon"],
            ["gool", "gool+tor", "gool"],
        ] {
            settings.protocol = sequence[1].into();
            settings.normalize_protocol_options();
            assert!(settings.validate().is_ok());
            assert_eq!(settings.gool_mode, "classic");
        }
        settings.protocol = "gool".into();
        settings.normalize_protocol_options();
        assert!(settings.validate().is_ok());
        assert_eq!(settings.gool_mode, "classic");
    }
}
