package com.firstham.aethergui;

import java.util.Map;

/**
 * Data-plane mapping for full-device Tor chaining (dev.017 CHANGE 7).
 *
 * <p>dev.016 exposed Tor as a separate SOCKS proxy on 127.0.0.1:1821 while ordinary device traffic
 * still left through the base Core/WARP SOCKS listener. The required product behavior for a
 * combined "base + Tor" protocol entry is the same topology the Psiphon chain uses: the
 * <em>final</em> egress for normal traffic — the HEV TUN bridge in Device VPN mode and the public
 * proxy ports 1818/1819 — must be the Tor exit, with the base tunnel acting only as the underlay
 * that carries Tor's own upstream.
 *
 * <p>The official Core provides this natively: {@code AETHER_TOR=chain} runs
 * aether → warp → tor → internet, the base SOCKS listener keeps serving the underlay, and the Tor
 * SOCKS listener on {@link PrivacyRuntimeConfig#TOR_SOCKS} becomes the effective tunnel target.
 * The Tor listener also accepts SOCKS5 UDP ASSOCIATE and carries port-53 datagrams as DNS over the
 * Tor connector (verified in the official source's socks.rs), so HEV's native 'udp' mode keeps
 * device DNS working through the Tor chain. Nothing here forks or patches the Core: the app only
 * chooses which listener each consumer targets, exactly like {@link PsiphonChainRouting}.
 */
final class TorChainRouting {
    /**
     * Private bind address for the Core's base SOCKS listener while a Tor chain is active. Shared
     * with the Psiphon chain's internal port: the two chains are mutually exclusive at the
     * settings level, so the same address can serve both without a double-bind.
     */
    static final String INTERNAL_CORE_SOCKS = PsiphonChainRouting.INTERNAL_CORE_SOCKS;

    static boolean chainActive(Map<String, ?> settings) {
        // torProxy stays the single persisted Tor switch. When a combined + Tor protocol entry is
        // selected the chain is the full-device mode; with torMode absent the legacy side-proxy
        // behavior is preserved exactly (dev.016 contract).
        return CoreSettings.enabled(settings, "torProxy") && "chain".equals(CoreSettings.string(settings, "torMode"));
    }

    // --- Protocol selector combined entries (CHANGE 7 / 13) --------------------------------
    // Protocol dropdown indices 8/9/10 are "base + Tor" combinations for WireGuard, gool and
    // MASQUE. Tor combinations stay gated on TOR_CHAIN capability: only physically verified
    // combinations get an entry, and the gate is not enabled until the physical validation passes.
    // Smart Connect, MIM and every blocked combination deliberately get no combined entry.

    static int combinedTorProtocolIndex(int baseIndex) {
        // The storage-index mapping is static; the gating of whether the entries are OFFERED
        // lives in ProtocolOrder.displayIndices (capability + Proxy mode), not here.
        switch (baseIndex) {
            case 1: return 8; // WireGuard + Tor
            case 2: return 9; // gool + Tor
            case 0: return 10; // MASQUE + Tor
            default: return -1;
        }
    }

    /** The base protocol a combined Tor entry stands for; -1 when the entry is not a Tor entry. */
    static int combinedTorBaseIndex(int selection) {
        switch (selection) {
            case 8: return 1; // WireGuard
            case 9: return 2; // gool
            case 10: return 0; // MASQUE
            default: return -1;
        }
    }

    /** The SOCKS address HEV, readiness and the traffic gate must target while Tor is the exit. */
    static String effectiveSocks(Map<String, ?> settings, String mode, String savedSocks) {
        if (chainActive(settings)) return PrivacyRuntimeConfig.TOR_SOCKS;
        return ProxyMode.socksAddress(mode, savedSocks);
    }

    /** The address the Core must bind its base SOCKS listener to while Tor is the exit. */
    static String coreSocksBind(Map<String, ?> settings, String mode, String savedSocks) {
        if (chainActive(settings)) return INTERNAL_CORE_SOCKS;
        return ProxyMode.socksAddress(mode, savedSocks);
    }

    private TorChainRouting() { }
}
