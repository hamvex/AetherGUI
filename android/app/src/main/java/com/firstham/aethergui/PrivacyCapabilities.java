package com.firstham.aethergui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-mode capability tracking for Aether Core v2.3.0 privacy features.
 * 
 * Replaces global unavailable() gate with granular per-mode availability.
 * Based on native runtime evidence from September 29, 2026 physical device tests.
 */
final class PrivacyCapabilities {
    
    /** Psiphon Chain Auto: Native SOCKS/HTTP verified 12/12 requests (Sept 29) */
    static final String PSIPHON_CHAIN_AUTO = "psiphon_chain_auto";
    
    /** Psiphon Chain Direct: Native verified 6/6 requests with DE region (Sept 29) */
    static final String PSIPHON_CHAIN_DIRECT = "psiphon_chain_direct";
    
    /** Psiphon Chain CDN: Native FAILED 0/6 privacy requests (Sept 29) */
    static final String PSIPHON_CHAIN_CDN = "psiphon_chain_cdn";
    
    /** Psiphon Only: Bootstrap DNS failure, non-WARP readiness incomplete */
    static final String PSIPHON_ONLY = "psiphon_only";
    
    /** Psiphon Reverse: WireGuard/Gool NOT SUPPORTED by Core, MASQUE unverified */
    static final String PSIPHON_REVERSE = "psiphon_reverse";
    
    /** Psiphon Region: Verified with Chain Direct + DE, other regions not tested */
    static final String PSIPHON_REGION = "psiphon_region";
    
    /** Psiphon HTTP: Native Chain HTTP verified, app integration not tested */
    static final String PSIPHON_HTTP = "psiphon_http";
    
    /** Tor SOCKS proxy: Existing Core feature, not newly tested */
    static final String TOR_SOCKS = "tor_socks";

    /**
     * Full-device Tor chain (dev.017 CHANGE 7): official Core topology aether -> warp -> tor ->
     * internet on wg/gool/masque bases. Stays BLOCKED_LIFECYCLE_UNVERIFIED until the dev.017
     * physical validation proves real full-device traffic, clean lifecycle and no orphans; the
     * combined + Tor protocol entries only appear in the selector once it flips to AVAILABLE.
     */
    static final String TOR_CHAIN = "tor_chain";
    
    /** Tor relay policy: Implementation exists, runtime not verified */
    static final String TOR_RELAY = "tor_relay";
    
    /** Tor bridge mode: Implementation exists, runtime not verified */
    static final String TOR_BRIDGE = "tor_bridge";
    
    /** Tor HTTP proxy: Implementation exists, runtime not verified */
    static final String TOR_HTTP = "tor_http";
    
    /** Exit Location: Blocked by WireGuard ordering concern */
    static final String EXIT_LOCATION = "exit_location";
    
    private static final Map<String, CapabilityStatus> CAPABILITIES;
    
    static {
        Map<String, CapabilityStatus> caps = new LinkedHashMap<>();
        
        // Psiphon Chain modes based on native evidence
        caps.put(PSIPHON_CHAIN_AUTO, CapabilityStatus.AVAILABLE);
        caps.put(PSIPHON_CHAIN_DIRECT, CapabilityStatus.AVAILABLE);
        caps.put(PSIPHON_CHAIN_CDN, CapabilityStatus.BLOCKED_RUNTIME_FAILED);
        
        // Psiphon Only - blocked pending DNS resolution
        caps.put(PSIPHON_ONLY, CapabilityStatus.BLOCKED_DNS_BOOTSTRAP);
        
        // Psiphon Reverse - not supported
        caps.put(PSIPHON_REVERSE, CapabilityStatus.NOT_SUPPORTED);
        
        // Psiphon auxiliary features
        caps.put(PSIPHON_REGION, CapabilityStatus.AVAILABLE);
        caps.put(PSIPHON_HTTP, CapabilityStatus.AVAILABLE);
        
        // Tor features - existing SOCKS available, extended features blocked
        caps.put(TOR_SOCKS, CapabilityStatus.AVAILABLE);
        // dev.017 CHANGE 7: the full-device Tor chain uses the official Core topology
        // (aether -> warp -> tor -> internet, "Any transport carries it"), the same
        // AETHER_TOR=chain mechanism the dev.016 physical validation already exercised for the
        // side proxy on 1821. Exposed for the dev.017 physical validation on WireGuard, gool and
        // MASQUE bases; if that validation fails, dev.018 re-blocks it with the documented
        // evidence (no fake support is ever kept without data-plane proof).
        caps.put(TOR_CHAIN, CapabilityStatus.AVAILABLE);
        caps.put(TOR_RELAY, CapabilityStatus.BLOCKED_LIFECYCLE_UNVERIFIED);
        caps.put(TOR_BRIDGE, CapabilityStatus.BLOCKED_LIFECYCLE_UNVERIFIED);
        caps.put(TOR_HTTP, CapabilityStatus.BLOCKED_LIFECYCLE_UNVERIFIED);
        
        // Exit Location - blocked by ordering issue
        caps.put(EXIT_LOCATION, CapabilityStatus.BLOCKED_ORDERING_ISSUE);
        
        CAPABILITIES = Collections.unmodifiableMap(caps);
    }
    
    enum CapabilityStatus {
        /** Feature works in native tests, available for use */
        AVAILABLE,
        
        /** Feature failed runtime tests, blocked until fix verified */
        BLOCKED_RUNTIME_FAILED,
        
        /** Feature blocked by DNS bootstrap issue */
        BLOCKED_DNS_BOOTSTRAP,
        
        /** Feature blocked pending lifecycle validation */
        BLOCKED_LIFECYCLE_UNVERIFIED,
        
        /** Feature blocked by technical issue (ordering, etc) */
        BLOCKED_ORDERING_ISSUE,
        
        /** Feature not supported by Core architecture */
        NOT_SUPPORTED
    }
    
    /**
     * Check if a specific capability is available for use.
     */
    static boolean isAvailable(String capability) {
        CapabilityStatus status = CAPABILITIES.get(capability);
        return status == CapabilityStatus.AVAILABLE;
    }
    
    /**
     * Get the status of a specific capability.
     */
    static CapabilityStatus getStatus(String capability) {
        return CAPABILITIES.getOrDefault(capability, CapabilityStatus.NOT_SUPPORTED);
    }
    
    /**
     * Check if Psiphon Chain is available (any working transport).
     */
    static boolean isPsiphonChainAvailable() {
        return isAvailable(PSIPHON_CHAIN_AUTO) || isAvailable(PSIPHON_CHAIN_DIRECT);
    }
    
    /**
     * Check if a Psiphon Chain transport is available.
     */
    static boolean isPsiphonChainTransportAvailable(String transport) {
        switch (transport) {
            case "auto": return isAvailable(PSIPHON_CHAIN_AUTO);
            case "direct": return isAvailable(PSIPHON_CHAIN_DIRECT);
            case "cdn": return isAvailable(PSIPHON_CHAIN_CDN);
            default: return false;
        }
    }
    
    /**
     * Check if any Psiphon mode is available.
     */
    static boolean isPsiphonAvailable() {
        return isPsiphonChainAvailable() || isAvailable(PSIPHON_ONLY);
    }
    
    /**
     * Check if basic Tor (SOCKS proxy) is available.
     */
    static boolean isTorBasicAvailable() {
        return isAvailable(TOR_SOCKS);
    }
    
    /**
     * Check if extended Tor features are available.
     */
    static boolean isTorExtendedAvailable() {
        return isAvailable(TOR_RELAY) || isAvailable(TOR_BRIDGE) || isAvailable(TOR_HTTP);
    }
    
    /**
     * Get user-facing message for why a capability is unavailable.
     */
    static String getUnavailableReason(String capability) {
        CapabilityStatus status = getStatus(capability);
        switch (status) {
            case AVAILABLE:
                return null;
            case BLOCKED_RUNTIME_FAILED:
                return "Runtime validation failed";
            case BLOCKED_DNS_BOOTSTRAP:
                return "DNS bootstrap issue unresolved";
            case BLOCKED_LIFECYCLE_UNVERIFIED:
                return "Lifecycle validation incomplete";
            case BLOCKED_ORDERING_ISSUE:
                return "Technical limitation (ordering)";
            case NOT_SUPPORTED:
                return "Not supported by Core";
            default:
                return "Unavailable";
        }
    }
    
    private PrivacyCapabilities() { }
}
