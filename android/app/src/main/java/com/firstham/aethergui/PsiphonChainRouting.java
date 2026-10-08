package com.firstham.aethergui;

import java.util.Map;

/**
 * Data-plane mapping for full-device Psiphon chaining (dev.016).
 *
 * <p>dev.015 exposed Psiphon as separate side proxies (SOCKS 1822 / HTTP 1824) while ordinary
 * device traffic still left through the base Core/WARP SOCKS listener on 127.0.0.1:1819. The
 * required product behavior is the opposite: when a Psiphon Chain combination is active, the
 * <em>final</em> egress for normal traffic — the HEV TUN bridge in Device VPN mode and the public
 * proxy ports 1818/1819 in any mode — must be the Psiphon exit, with the base tunnel acting only
 * as the underlay that carries the Psiphon helper's own upstream.
 *
 * <p>This class is the single source of truth for that remapping. It never forks or patches the
 * official Core: the Core simply gets two different listener addresses. In Chain mode the base
 * SOCKS listener moves to a private internal port (the Core's own upstream for the helper), the
 * Psiphon side SOCKS at {@link PrivacyRuntimeConfig#PSIPHON_SOCKS} becomes the effective tunnel
 * target, and the app relays the public 1818/1819 contract to the Psiphon listeners so no user
 * ever has to know about 1822/1824. When Psiphon is off, every mapping here returns the existing
 * dev.015 address unchanged.
 */
final class PsiphonChainRouting {
    /**
     * Private bind address for the Core's base SOCKS listener while Chain is active. 18193 sits in
     * the same loopback range as the other internal listeners, is outside the published
     * 1818/1819/1821/1822/1824 contract, and is checked for collisions against every other
     * listener in the plan.
     */
    static final String INTERNAL_CORE_SOCKS = "127.0.0.1:18193";

    static boolean chainActive(Map<String, ?> settings) {
        return "chain".equals(CoreSettings.string(settings, "psiphonMode"));
    }

    // --- Protocol selector combined entries (dev.016 PART 4) -------------------------------
    // Protocol dropdown indices 5/6/7 are "base + Psiphon Chain" combinations for WireGuard,
    // gool and MASQUE; the blocked topologies and unverified combinations (Smart, MIM, reverse)
    // deliberately get no combined entry.

    static int combinedProtocolIndex(int baseIndex) {
        // Dropdown order: 5 = WireGuard(1) + Psiphon, 6 = gool(2) + Psiphon, 7 = MASQUE(0) + Psiphon.
        switch (baseIndex) {
            case 1: return 5;
            case 2: return 6;
            case 0: return 7;
            default: return -1;
        }
    }

    /** The base protocol a combined entry stands for; -1 when the entry is not a combined one. */
    static int combinedBaseIndex(int selection) {
        switch (selection) {
            case 5: return 1; // WireGuard
            case 6: return 2; // gool
            case 7: return 0; // MASQUE
            default: return -1;
        }
    }

    /**
     * The SOCKS address that HEV, readiness checks, traffic validation, health checks and location
     * lookups must use: the Psiphon final proxy when Chain is active, otherwise the base listener.
     */
    static String effectiveSocks(Map<String, ?> settings, String mode, String savedSocks) {
        if (chainActive(settings)) return PrivacyRuntimeConfig.PSIPHON_SOCKS;
        return ProxyMode.socksAddress(mode, savedSocks);
    }

    /**
     * The address the Core must actually bind its base SOCKS listener to: internal-only while
     * Chain is active (it becomes the helper's underlay, not the user-facing exit), otherwise the
     * existing public address.
     */
    static String coreSocksBind(Map<String, ?> settings, String mode, String savedSocks) {
        if (chainActive(settings)) return INTERNAL_CORE_SOCKS;
        return ProxyMode.socksAddress(mode, savedSocks);
    }

    private PsiphonChainRouting() { }
}
