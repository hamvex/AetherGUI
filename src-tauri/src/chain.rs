//! Privacy-chain runtime: environment variables for Psiphon/Tor chains and the public
//! 1818/1819 port relays that mirror Android `PublicPortRelays`.
//!
//! Topology (Android dev.016-020 parity, verified against core v2.1.0 on Windows in
//! work/windows-v212-transformation-20261003/core-smoke-results.json):
//!
//! Plain protocols:
//!   core binds SOCKS 1819 (+ HTTP 1818 when we set AETHER_HTTP_PROXY) directly. No relays.
//!
//! +Psiphon / +Tor chains:
//!   core binds its base SOCKS on an internal loopback port (we remap AETHER_SOCKS there),
//!   the privacy helper binds its own SOCKS/HTTP (1822/1824 psiphon, 1821/1825 tor), and the
//!   GUI binds the public 1818/1819 and byte-pipes every connection to the privacy egress so
//!   the public contract always carries the FINAL selected path — never the WARP underlay
//!   (the core's own 1818/1819 in chain mode serve the underlay; verified warp=on IR while
//!   1822 served DE warp=off).

use crate::protocol::{
    PrivacyChain, PSIPHON_HTTP, PSIPHON_SOCKS, PUBLIC_HTTP, PUBLIC_SOCKS, TOR_HTTP, TOR_SOCKS,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use tokio::net::{TcpListener, TcpStream};

/// Chain configuration derived from settings + the resolved internal underlay port.
#[derive(Debug, Clone)]
pub struct ChainRuntime {
    pub chain: PrivacyChain,
    /// Internal address the core's base SOCKS is bound to (underlay feeding the helper).
    pub internal_socks: String,
    pub psiphon_transport: String,
    /// Two-letter region or empty (Automatic).
    pub psiphon_region: String,
    /// Directory for tor state (consensus cache).
    pub tor_dir: String,
}

impl ChainRuntime {
    /// Effective egress listeners for the active chain.
    pub fn egress(&self) -> (&'static str, &'static str) {
        match self.chain {
            PrivacyChain::Psiphon => (PSIPHON_SOCKS, PSIPHON_HTTP),
            PrivacyChain::Tor => (TOR_SOCKS, TOR_HTTP),
            PrivacyChain::None => (PUBLIC_SOCKS, PUBLIC_HTTP),
        }
    }

    /// Environment variables to layer onto the core process for this chain.
    pub fn environment(&self) -> HashMap<String, String> {
        let mut env = HashMap::new();
        match self.chain {
            PrivacyChain::None => {
                // Plain: the core owns the public ports directly.
                env.insert("AETHER_SOCKS".into(), PUBLIC_SOCKS.into());
                env.insert("AETHER_HTTP_PROXY".into(), PUBLIC_HTTP.into());
            }
            PrivacyChain::Psiphon => {
                env.insert("AETHER_SOCKS".into(), self.internal_socks.clone());
                env.insert("AETHER_PSIPHON".into(), "chain".into());
                env.insert("AETHER_PSIPHON_BIND".into(), PSIPHON_SOCKS.into());
                // Auto-provision the Psiphon HTTP listener (Android parity; the GUI relay for
                // 1818 feeds from it).
                env.insert("AETHER_PSIPHON_HTTP".into(), PSIPHON_HTTP.into());
                env.insert("AETHER_PSIPHON_MODE".into(), self.psiphon_transport.clone());
                if !self.psiphon_region.trim().is_empty() {
                    env.insert(
                        "AETHER_PSIPHON_REGION".into(),
                        self.psiphon_region.trim().to_uppercase(),
                    );
                }
                if let Some(dir) = self.tor_dir_if_needed() {
                    env.insert("AETHER_PSIPHON_DIR".into(), dir);
                }
                // AETHER_PSIPHON_BIN: the core looks beside itself / in ./pt first; the sidecar
                // layout installs pt\psiphon-tunnel-core.exe next to aether.exe, so no explicit
                // env is needed. Keep dir pinned to the app data dir for state.
            }
            PrivacyChain::Tor => {
                env.insert("AETHER_SOCKS".into(), self.internal_socks.clone());
                env.insert("AETHER_TOR".into(), "chain".into());
                env.insert("AETHER_TOR_BIND".into(), TOR_SOCKS.into());
                env.insert("AETHER_TOR_HTTP".into(), TOR_HTTP.into());
                // Android chain mode pins bridges off (direct-only through the underlay).
                env.insert("AETHER_TOR_BRIDGES".into(), "off".into());
                if let Some(dir) = self.tor_dir_if_needed() {
                    env.insert("AETHER_TOR_DIR".into(), dir);
                }
            }
        }
        env
    }

    /// Data dir for tor state, if the caller wants one pinned.
    pub fn tor_dir_if_needed(&self) -> Option<String> {
        if self.tor_dir.trim().is_empty() {
            None
        } else {
            Some(self.tor_dir.trim().to_string())
        }
    }
}

/// Public 1818/1819 relays: accept on the public ports, forward verbatim to the chain egress.
/// Plain protocols never start relays (the core owns the ports).
pub struct PublicPortRelays {
    generation: Arc<AtomicU64>,
    running: Arc<AtomicBool>,
}

impl PublicPortRelays {
    pub fn new() -> Self {
        Self {
            generation: Arc::new(AtomicU64::new(0)),
            running: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Bind both relays feeding the given egress pair. Fails with a meaningful error if either
    /// public port is occupied (we never kill the occupying process). `lan` binds the relays
    /// on all interfaces so private-network devices can reach the final chain egress.
    ///
    /// When `process` is given, its generation is watched: once the core stops or restarts,
    /// the relays release their listeners — a chain egress without a core is dead, and no
    /// error path may leave the public ports listening (defect found in physical validation).
    pub async fn start(
        &self,
        chain: PrivacyChain,
        lan: bool,
        process: Option<&std::sync::Arc<crate::process::ProcessManager>>,
    ) -> Result<u64, String> {
        if chain == PrivacyChain::None {
            return Ok(self.generation.load(Ordering::SeqCst));
        }
        let (socks_target, http_target): (&'static str, &'static str) = match chain {
            PrivacyChain::Psiphon => (PSIPHON_SOCKS, PSIPHON_HTTP),
            PrivacyChain::Tor => (TOR_SOCKS, TOR_HTTP),
            PrivacyChain::None => unreachable!(),
        };
        let socks_bind = if lan { "0.0.0.0:1819" } else { PUBLIC_SOCKS };
        let http_bind = if lan { "0.0.0.0:1818" } else { PUBLIC_HTTP };
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;

        let socks_listener = bind_public(socks_bind).await?;
        let http_listener = bind_public(http_bind).await?;
        self.running.store(true, Ordering::SeqCst);

        spawn_relay(socks_listener, socks_target, generation, self.running.clone());
        spawn_relay(http_listener, http_target, generation, self.running.clone());

        if let Some(manager) = process {
            let running = self.running.clone();
            let manager = std::sync::Arc::clone(manager);
            tokio::spawn(async move {
                let baseline = manager.generation().await;
                loop {
                    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                    if !running.load(Ordering::SeqCst) {
                        break;
                    }
                    if manager.generation().await != baseline {
                        // The core stopped or restarted: release the public listeners.
                        running.store(false, Ordering::SeqCst);
                        break;
                    }
                }
            });
        }
        Ok(generation)
    }

    /// Stop all relay tasks and release both public listeners.
    pub async fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        self.generation.fetch_add(1, Ordering::SeqCst);
        // Listeners are dropped by their owning tasks once `running` flips false and the
        // accept loop observes it; bounded wait is enforced by the task loop's tick check.
    }
}

impl Default for PublicPortRelays {
    fn default() -> Self {
        Self::new()
    }
}

async fn bind_public(address: &str) -> Result<TcpListener, String> {
    let addr: SocketAddr = address
        .parse()
        .map_err(|_| format!("Invalid relay address {address}"))?;
    TcpListener::bind(addr)
        .await
        .map_err(|error| {
            format!(
                "Aethon could not open its local proxy port {address} ({error}). \
                 Another application may be using it; Aethon never closes other applications' \
                 ports. Close the conflicting application or check Windows reserved port \
                 ranges ('netsh interface ipv4 show excludedportrange protocol=tcp') and \
                 try again."
            )
        })
}

fn spawn_relay(
    listener: TcpListener,
    target: &'static str,
    generation: u64,
    running: Arc<AtomicBool>,
) {
    let target_addr: SocketAddr = target.parse().expect("constant relay target parses");
    tokio::spawn(async move {
        loop {
            if !running.load(Ordering::SeqCst) || generation == 0 {
                break;
            }
            let accepted = tokio::select! {
                result = listener.accept() => Some(result),
                _ = tokio::time::sleep(std::time::Duration::from_millis(250)) => None,
            };
            let (client, _peer) = match accepted {
                Some(Ok(pair)) => pair,
                Some(Err(_)) => {
                    // Transient accept error; brief backoff then re-check running flag.
                    tokio::time::sleep(std::time::Duration::from_millis(250)).await;
                    continue;
                }
                None => continue,
            };
            let target = target_addr;
            tokio::spawn(async move {
                if let Err(error) = relay_connection(client, target).await {
                    let _ = error; // Connection-level failures are expected noise.
                }
            });
        }
    });
}

async fn relay_connection(client: TcpStream, target: SocketAddr) -> std::io::Result<()> {
    let mut client = client;
    let mut upstream = TcpStream::connect(target).await?;
    // Bidirectional byte pipe with no interpretation: SOCKS remains SOCKS, HTTP CONNECT
    // remains HTTP CONNECT on the target listener.
    tokio::io::copy_bidirectional(&mut client, &mut upstream).await.map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::PrivacyChain;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn runtime(chain: PrivacyChain) -> ChainRuntime {
        ChainRuntime {
            chain,
            internal_socks: "127.0.0.1:18193".into(),
            psiphon_transport: "auto".into(),
            psiphon_region: String::new(),
            tor_dir: String::new(),
        }
    }

    #[test]
    fn plain_protocols_keep_the_public_contract_on_the_core() {
        let env = runtime(PrivacyChain::None).environment();
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:1819");
        assert_eq!(env["AETHER_HTTP_PROXY"], "127.0.0.1:1818");
        assert!(!env.contains_key("AETHER_PSIPHON"));
        assert!(!env.contains_key("AETHER_TOR"));
    }

    #[test]
    fn psiphon_chain_remaps_the_underlay_and_provisions_both_listeners() {
        let env = runtime(PrivacyChain::Psiphon).environment();
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:18193");
        assert_eq!(env["AETHER_PSIPHON"], "chain");
        assert_eq!(env["AETHER_PSIPHON_BIND"], "127.0.0.1:1822");
        assert_eq!(env["AETHER_PSIPHON_HTTP"], "127.0.0.1:1824");
        assert_eq!(env["AETHER_PSIPHON_MODE"], "auto");
        assert!(!env.contains_key("AETHER_PSIPHON_REGION"));
        // The core's own HTTP proxy is not set: 1818 is owned by the GUI relay.
        assert!(!env.contains_key("AETHER_HTTP_PROXY"));
    }

    #[test]
    fn psiphon_region_is_uppercased_and_omitted_when_automatic() {
        let mut rt = runtime(PrivacyChain::Psiphon);
        rt.psiphon_region = "de".into();
        let env = rt.environment();
        assert_eq!(env["AETHER_PSIPHON_REGION"], "DE");
    }

    #[test]
    fn psiphon_direct_transport_is_forwarded() {
        let mut rt = runtime(PrivacyChain::Psiphon);
        rt.psiphon_transport = "direct".into();
        let env = rt.environment();
        assert_eq!(env["AETHER_PSIPHON_MODE"], "direct");
    }

    #[test]
    fn tor_chain_pins_bridges_off_and_provisions_http() {
        let mut rt = runtime(PrivacyChain::Tor);
        rt.tor_dir = "C:/data/aether-tor".into();
        let env = rt.environment();
        assert_eq!(env["AETHER_TOR"], "chain");
        assert_eq!(env["AETHER_TOR_BIND"], "127.0.0.1:1821");
        assert_eq!(env["AETHER_TOR_HTTP"], "127.0.0.1:1825");
        assert_eq!(env["AETHER_TOR_BRIDGES"], "off");
        assert_eq!(env["AETHER_TOR_DIR"], "C:/data/aether-tor");
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:18193");
        assert!(!env.contains_key("AETHER_HTTP_PROXY"));
    }

    #[test]
    fn egress_pair_follows_the_chain() {
        assert_eq!(runtime(PrivacyChain::Psiphon).egress().0, "127.0.0.1:1822");
        assert_eq!(runtime(PrivacyChain::Psiphon).egress().1, "127.0.0.1:1824");
        assert_eq!(runtime(PrivacyChain::Tor).egress().0, "127.0.0.1:1821");
        assert_eq!(runtime(PrivacyChain::Tor).egress().1, "127.0.0.1:1825");
        assert_eq!(runtime(PrivacyChain::None).egress().0, "127.0.0.1:1819");
    }

    #[tokio::test]
    async fn relays_do_not_start_for_plain_protocols() {
        let relays = PublicPortRelays::new();
        let generation = relays.start(PrivacyChain::None, true, None).await.unwrap();
        assert_eq!(generation, relays.generation_load_for_test());
    }

    #[tokio::test]
    async fn relay_pipes_bytes_to_the_chain_egress() {
        // Echo server pretending to be the privacy egress.
        let echo = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let egress_port = echo.local_addr().unwrap().port();
        let echo_task = tokio::spawn(async move {
            while let Ok((mut socket, _)) = echo.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 512];
                    if let Ok(n) = socket.read(&mut buf).await {
                        let _ = socket.write_all(&buf[..n]).await;
                    }
                });
            }
        });
        // Relay bound on an ephemeral port standing in for 1819.
        let relay_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let relay_port = relay_listener.local_addr().unwrap().port();
        let running = Arc::new(AtomicBool::new(true));
        let running_in_loop = running.clone();
        let target: SocketAddr = format!("127.0.0.1:{egress_port}").parse().unwrap();
        // Reuse the production pipe on a dynamic target.
        let pump = tokio::spawn(async move {
            loop {
                let accepted = tokio::select! {
                    r = relay_listener.accept() => Some(r),
                    _ = tokio::time::sleep(std::time::Duration::from_millis(50)) => None,
                };
                match accepted {
                    Some(Ok((client, _))) => {
                        let t = target;
                        tokio::spawn(async move {
                            let _ = relay_connection(client, t).await;
                        });
                    }
                    Some(Err(_)) => break,
                    None => {
                        if !running_in_loop.load(Ordering::SeqCst) {
                            break;
                        }
                    }
                }
            }
        });
        let mut client = TcpStream::connect(format!("127.0.0.1:{relay_port}"))
            .await
            .unwrap();
        client.write_all(b"ping-through-relay").await.unwrap();
        let mut buf = [0u8; 64];
        let n = tokio::time::timeout(std::time::Duration::from_secs(3), client.read(&mut buf))
            .await
            .expect("echo within 3s")
            .unwrap();
        assert_eq!(&buf[..n], b"ping-through-relay");
        running.store(false, Ordering::SeqCst);
        pump.abort();
        echo_task.abort();
    }
}

impl PublicPortRelays { #[cfg(test)] pub fn generation_load_for_test(&self) -> u64 {
        self.generation.load(Ordering::SeqCst)
    }
}
