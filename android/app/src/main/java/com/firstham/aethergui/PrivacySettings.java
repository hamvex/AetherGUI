package com.firstham.aethergui;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

final class PrivacySettings {
    static final int SCHEMA = 1;
    static final Map<String, Object> DEFAULTS;
    static {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("coreSettingsSchema", SCHEMA);
        defaults.put("psiphonMode", "off");
        defaults.put("psiphonTransport", "auto");
        defaults.put("psiphonRegion", "");
        defaults.put("psiphonHttp", false);
        defaults.put("torRelayPolicy", "default");
        defaults.put("torRelayCount", 40);
        defaults.put("torBridgeMode", "off");
        defaults.put("torBridgeUri", "");
        defaults.put("torHttp", false);
        // dev.017 CHANGE 7: the Tor topology the user selected. "side" (default, also the empty
        // meaning for older builds) keeps the dev.016 separate SOCKS 1821 proxy contract; "chain"
        // makes Tor the full-device egress through a combined + Tor protocol entry.
        defaults.put("torMode", "side");
        defaults.put("exitLocationEnabled", false);
        defaults.put("exitLocationMode", "allow");
        defaults.put("exitLocationCountries", "");
        // dev.034: AETHER_EXIT_LOC_SECS (upstream default 60, max 86400): how often the Core
        // rechecks the exit country while connected; a policy-triggered drop/reconnect then
        // follows the upstream semantics.
        defaults.put("exitLocationSecs", 60);
        DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    /**
     * dev.017 CHANGE 5: Proxy mode exposes Aethon's own endpoints for external apps; the internal
     * full-device privacy chains are not meaningful there (the dropdown hides them). A saved
     * chain state from Device VPN mode is preserved untouched — validation only refuses to START
     * the full-device chain while the connection mode is Proxy, so switching back reactivates it.
     */
    static String invalidForMode(Map<String, ?> values, String protocol, String connectionMode) {
        boolean proxy = ProxyMode.enabled(connectionMode == null ? "" : connectionMode);
        if (proxy) {
            if (PsiphonChainRouting.chainActive(values)) return "psiphonMode";
            if (TorChainRouting.chainActive(values)) return "torMode";
        }
        return invalid(values, protocol);
    }

    static String invalid(Map<String, ?> values, String protocol) {
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            Object value = values.get(entry.getKey());
            if (value == null || !entry.getValue().getClass().isInstance(value)) return entry.getKey();
        }
        if (!Integer.valueOf(SCHEMA).equals(values.get("coreSettingsSchema"))) return "coreSettingsSchema";
        String mode = CoreSettings.string(values, "psiphonMode");
        if (!PsiphonConfiguration.TOPOLOGIES.contains(mode) && !mode.equals("reverse")) return "psiphonMode";
        if (!PsiphonConfiguration.CHAIN_TRANSPORTS.contains(CoreSettings.string(values, "psiphonTransport"))) return "psiphonTransport";
        String region = CoreSettings.string(values, "psiphonRegion");
        if (!region.isEmpty() && !region.matches("[A-Za-z]{2}")) return "psiphonRegion";
        if (mode.equals("reverse") && protocol != null && !CoreSettings.masque(protocol)) return "psiphonMode";
        String torMode = CoreSettings.string(values, "torMode");
        if (!Arrays.asList("side", "chain").contains(torMode)) return "torMode";
        // Psiphon and Tor are mutually exclusive exits. An ACTIVE Psiphon topology together with
        // an ACTIVE Tor chain (or the dev.016 Tor side switch, whose env is emitted alongside the
        // Psiphon chain) would make the Core run both privacy networks at once — an ambiguous
        // topology. Both sides must be ACTIVE for this to be a real conflict: residual fields of
        // a deactivated chain (psiphonMode=off, or torMode=side set while torProxy is false) never
        // block an unrelated protocol (dev.019 ISSUE 1; the exact dev.018 trap state
        // psiphonMode=chain + torProxy=true is additionally repaired by the migration and can no
        // longer be produced by the Protocol selector, which writes the complete state atomically).
        boolean psiphonActive = !mode.equals("off");
        if (psiphonActive && TorChainRouting.chainActive(values)) return "psiphonMode";
        // Only wireguard/gool/masque base protocols are verified Tor-chain bases so far.
        if (torChainActiveOnly(values) && protocol != null
                && !"wg".equals(protocol) && !"gool".equals(protocol) && !"masque".equals(protocol)) return "torMode";
        if (psiphonActive && CoreSettings.enabled(values, "torProxy")) return "psiphonMode";
        if (psiphonActive && !CoreSettings.string(values, "upstreamProxy").isEmpty()) return "psiphonMode";
        if (!Arrays.asList("default", "off", "additional", "only").contains(CoreSettings.string(values, "torRelayPolicy"))) return "torRelayPolicy";
        int count = (Integer)values.get("torRelayCount");
        if (count < 1 || count > 400) return "torRelayCount";
        String bridgeMode = CoreSettings.string(values, "torBridgeMode");
        if (!Arrays.asList("off", "file").contains(bridgeMode)) return "torBridgeMode";
        if (bridgeMode.equals("file") && !CoreSettings.string(values, "torRelayPolicy").equals("default")) return "torRelayPolicy";
        String uri = CoreSettings.string(values, "torBridgeUri");
        if ((!uri.isEmpty() && !documentUri(uri)) || (bridgeMode.equals("file") && uri.isEmpty())) return "torBridgeUri";
        if (!Arrays.asList("allow", "exclude").contains(CoreSettings.string(values, "exitLocationMode"))) return "exitLocationMode";
        String countries = CoreSettings.string(values, "exitLocationCountries");
        if ((!countries.isEmpty() && !countries.matches("[A-Za-z]{2}(,[A-Za-z]{2})*")) ||
                (CoreSettings.enabled(values, "exitLocationEnabled") && countries.isEmpty())) return "exitLocationCountries";
        int exitSecs = (Integer)values.get("exitLocationSecs");
        if (exitSecs < 1 || exitSecs > 86400) return "exitLocationSecs";
        return null;
    }

    static boolean documentUri(String value) {
        try {
            URI uri = new URI(value);
            return "content".equals(uri.getScheme()) && uri.getRawAuthority() != null &&
                    !uri.getRawAuthority().isEmpty() && uri.getRawUserInfo() == null &&
                    uri.getRawFragment() == null && uri.getPath() != null && !uri.getPath().isEmpty();
        } catch (Exception invalid) { return false; }
    }

    /** The full-device Tor chain is selected, with Psiphon fully off (the exclusivity above). */
    private static boolean torChainActiveOnly(Map<String, ?> values) {
        return TorChainRouting.chainActive(values);
    }

    /**
     * Check if specific privacy settings are unavailable due to capability limitations.
     * Returns the first unavailable setting name, or null if all current settings are available.
     * 
     * This replaces the old global gate with per-mode capability checking.
     */
    static String unavailable(Map<String, ?> values) {
        String psiphon = CoreSettings.string(values, "psiphonMode");
        String psiphonTransport = CoreSettings.string(values, "psiphonTransport");
        
        // Check Psiphon modes
        if ("only".equals(psiphon) && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_ONLY)) {
            return "psiphonMode";
        }
        if ("chain".equals(psiphon)) {
            if (!PrivacyCapabilities.isPsiphonChainAvailable()) {
                return "psiphonMode";
            }
            if (!PrivacyCapabilities.isPsiphonChainTransportAvailable(psiphonTransport)) {
                return "psiphonTransport";
            }
        }
        if ("reverse".equals(psiphon) && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_REVERSE)) {
            return "psiphonMode";
        }
        
        // Check Tor extended features only if Tor proxy is enabled
        if (CoreSettings.enabled(values, "torProxy")) {
            // The full-device Tor chain stays unavailable until physically verified; a saved
            // torMode=chain from an experiment must be rejected before any process starts.
            if (TorChainRouting.chainActive(values) && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_CHAIN)) {
                return "torMode";
            }
            String torRelay = CoreSettings.string(values, "torRelayPolicy");
            if (!"default".equals(torRelay) && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_RELAY)) {
                return "torRelayPolicy";
            }
            
            String torBridge = CoreSettings.string(values, "torBridgeMode");
            if (!"off".equals(torBridge) && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_BRIDGE)) {
                return "torBridgeMode";
            }
            
            if (CoreSettings.enabled(values, "torHttp") && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_HTTP)) {
                return "torHttp";
            }
        }
        
        // Check exit location
        if (CoreSettings.enabled(values, "exitLocationEnabled") && !PrivacyCapabilities.isAvailable(PrivacyCapabilities.EXIT_LOCATION)) {
            return "exitLocationEnabled";
        }
        
        return null;
    }

    static String exitPolicy(Map<String, ?> values) {
        if (!CoreSettings.enabled(values, "exitLocationEnabled")) return "";
        String countries = String.join(",", new LinkedHashSet<>(Arrays.asList(CoreSettings.string(values, "exitLocationCountries").toUpperCase(Locale.ROOT).split(","))));
        return ("exclude".equals(CoreSettings.string(values, "exitLocationMode")) ? "!" : "") + countries;
    }

    static Map<String, Object> disableUnavailable(Map<String, ?> source) {
        Map<String, Object> values = CoreSettings.values(source);
        values.put("psiphonMode", "off");
        values.put("torRelayPolicy", "default");
        values.put("torBridgeMode", "off");
        values.put("torHttp", false);
        values.put("exitLocationEnabled", false);
        return values;
    }

    private PrivacySettings() { }
}
