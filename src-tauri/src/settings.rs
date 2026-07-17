use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, path::Path};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub language: String,
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
    pub ip_mode: String,
    pub obfuscation: String,
    pub masque_transport: String,
    pub socks_address: String,
    pub allow_remote_listener: bool,
    pub peer: String,
    pub wg_keepalive: u16,
    pub stall_timeout: u64,
    pub watchdog: bool,
    pub config_path: String,
    pub wg_config_path: String,
    pub masque_config_path: String,
    pub quick_reconnect: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: "en".into(),
            connection_mode: "vpn".into(),
            routing_mode: "bypass-local".into(),
            dns_leak_protection: true,
            ipv6_behavior: "tunnel".into(),
            kill_switch: false,
            tun_mtu: 1500,
            split_applications: Vec::new(),
            route_exclusions: Vec::new(),
            protocol: "masque".into(),
            scan_mode: "balanced".into(),
            ip_mode: "v4".into(),
            obfuscation: "firewall".into(),
            masque_transport: "h3".into(),
            socks_address: "127.0.0.1:1819".into(),
            allow_remote_listener: false,
            peer: String::new(),
            wg_keepalive: 5,
            stall_timeout: 90,
            watchdog: true,
            config_path: String::new(),
            wg_config_path: String::new(),
            masque_config_path: String::new(),
            quick_reconnect: true,
        }
    }
}

impl Settings {
    pub fn validate(&self) -> Result<(), String> {
        one_of("language", &self.language, &["en", "fa"])?;
        one_of("connection mode", &self.connection_mode, &["vpn", "manual"])?;
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
        one_of("protocol", &self.protocol, &["masque", "wg", "gool"])?;
        one_of(
            "scan mode",
            &self.scan_mode,
            &["turbo", "balanced", "thorough", "stealth"],
        )?;
        one_of("IP mode", &self.ip_mode, &["v4", "v6", "both"])?;
        one_of("MASQUE transport", &self.masque_transport, &["h3", "h2"])?;
        let profiles = if self.protocol == "masque" {
            &["firewall", "gfw", "off"][..]
        } else {
            &["balanced", "aggressive", "light", "off"][..]
        };
        one_of("obfuscation profile", &self.obfuscation, profiles)?;
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
        ] {
            if !value.trim().is_empty()
                && (Path::new(value).file_name().is_none() || value.contains('\0'))
            {
                return Err(format!("Invalid {label} file path"));
            }
        }
        Ok(())
    }

    pub fn environment(&self, default_config: &Path) -> Result<HashMap<String, String>, String> {
        self.validate()?;
        let mut env = HashMap::from([
            ("AETHER_PROTOCOL".into(), self.protocol.clone()),
            ("AETHER_SCAN".into(), self.scan_mode.clone()),
            ("AETHER_IP".into(), self.ip_mode.clone()),
            ("AETHER_NOIZE".into(), self.obfuscation.clone()),
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
        if !self.peer.trim().is_empty() {
            env.insert(
                if self.protocol == "masque" {
                    "AETHER_PEER".into()
                } else {
                    "AETHER_WG_PEER".into()
                },
                self.peer.trim().into(),
            );
        }
        if self.protocol == "masque" {
            env.insert(
                "AETHER_MASQUE_HTTP2".into(),
                if self.masque_transport == "h2" {
                    "1"
                } else {
                    "0"
                }
                .into(),
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
        Ok(env)
    }
}

fn one_of(label: &str, value: &str, options: &[&str]) -> Result<(), String> {
    options
        .contains(&value)
        .then_some(())
        .ok_or_else(|| format!("Unsupported {label}: {value}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_map_to_documented_environment() {
        let env = Settings::default()
            .environment(Path::new("C:/data/aether.toml"))
            .unwrap();
        assert_eq!(env["AETHER_PROTOCOL"], "masque");
        assert_eq!(env["AETHER_MASQUE_HTTP2"], "0");
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:1819");
    }
    #[test]
    fn protocol_profiles_are_enforced() {
        let mut s = Settings::default();
        s.protocol = "wg".into();
        assert!(s.validate().is_err());
        s.obfuscation = "aggressive".into();
        assert!(s.validate().is_ok());
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
    fn language_is_persisted_but_not_forwarded_to_core() {
        let mut settings = Settings::default();
        settings.language = "fa".into();
        let json = serde_json::to_string(&settings).unwrap();
        assert!(json.contains("\"language\":\"fa\""));
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert!(!env.contains_key("AETHER_LANGUAGE"));
        settings.language = "ar".into();
        assert!(settings.validate().is_err());
    }

    #[test]
    fn wireguard_and_h2_use_exact_core_names() {
        let mut settings = Settings::default();
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
}
