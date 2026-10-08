package com.firstham.aethergui;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for per-mode privacy capability tracking.
 * Based on native test evidence from September 29, 2026.
 */
public class PrivacyCapabilitiesTest {
    
    @Test
    public void psiphonChainAutoIsAvailable() {
        assertTrue("Psiphon Chain Auto should be available (native tests passed 12/12)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_CHAIN_AUTO));
    }
    
    @Test
    public void psiphonChainDirectIsAvailable() {
        assertTrue("Psiphon Chain Direct should be available (native tests passed 6/6)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_CHAIN_DIRECT));
    }
    
    @Test
    public void psiphonChainCdnIsBlocked() {
        assertFalse("Psiphon CDN should be blocked (native tests failed 0/6)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_CHAIN_CDN));
        assertEquals(PrivacyCapabilities.CapabilityStatus.BLOCKED_RUNTIME_FAILED,
                PrivacyCapabilities.getStatus(PrivacyCapabilities.PSIPHON_CHAIN_CDN));
    }
    
    @Test
    public void psiphonOnlyIsBlocked() {
        assertFalse("Psiphon Only should be blocked (DNS bootstrap failure)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_ONLY));
        assertEquals(PrivacyCapabilities.CapabilityStatus.BLOCKED_DNS_BOOTSTRAP,
                PrivacyCapabilities.getStatus(PrivacyCapabilities.PSIPHON_ONLY));
    }
    
    @Test
    public void psiphonReverseNotSupported() {
        assertFalse("Psiphon Reverse should not be supported",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_REVERSE));
        assertEquals(PrivacyCapabilities.CapabilityStatus.NOT_SUPPORTED,
                PrivacyCapabilities.getStatus(PrivacyCapabilities.PSIPHON_REVERSE));
    }
    
    @Test
    public void psiphonRegionIsAvailable() {
        assertTrue("Psiphon Region should be available (tested with Direct+DE)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_REGION));
    }
    
    @Test
    public void psiphonHttpIsAvailable() {
        assertTrue("Psiphon HTTP should be available (native Chain HTTP verified)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_HTTP));
    }
    
    @Test
    public void psiphonChainIsAvailable() {
        assertTrue("Psiphon Chain should be available (Auto and Direct work)",
                PrivacyCapabilities.isPsiphonChainAvailable());
    }
    
    @Test
    public void psiphonIsAvailableOverall() {
        assertTrue("Some Psiphon mode should be available (Chain works)",
                PrivacyCapabilities.isPsiphonAvailable());
    }
    
    @Test
    public void psiphonChainTransportAvailabilityCorrect() {
        assertTrue("Chain Auto transport should be available",
                PrivacyCapabilities.isPsiphonChainTransportAvailable("auto"));
        assertTrue("Chain Direct transport should be available",
                PrivacyCapabilities.isPsiphonChainTransportAvailable("direct"));
        assertFalse("Chain CDN transport should be blocked",
                PrivacyCapabilities.isPsiphonChainTransportAvailable("cdn"));
        assertFalse("Invalid transport should not be available",
                PrivacyCapabilities.isPsiphonChainTransportAvailable("invalid"));
    }
    
    @Test
    public void torBasicIsAvailable() {
        assertTrue("Basic Tor SOCKS should be available",
                PrivacyCapabilities.isTorBasicAvailable());
        assertTrue("TOR_SOCKS capability should be available",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_SOCKS));
    }
    
    @Test
    public void torExtendedFeaturesBlocked() {
        assertFalse("Tor relay should be blocked pending lifecycle validation",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_RELAY));
        assertFalse("Tor bridge should be blocked pending lifecycle validation",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_BRIDGE));
        assertFalse("Tor HTTP should be blocked pending lifecycle validation",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_HTTP));
        assertFalse("Extended Tor features should not be available",
                PrivacyCapabilities.isTorExtendedAvailable());
    }
    
    @Test
    public void exitLocationIsBlocked() {
        assertFalse("Exit Location should be blocked (ordering issue)",
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.EXIT_LOCATION));
        assertEquals(PrivacyCapabilities.CapabilityStatus.BLOCKED_ORDERING_ISSUE,
                PrivacyCapabilities.getStatus(PrivacyCapabilities.EXIT_LOCATION));
    }
    
    @Test
    public void unavailableReasonsAreAccurate() {
        assertNull("Available feature should have no unavailable reason",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.PSIPHON_CHAIN_AUTO));
        
        assertEquals("Runtime validation failed",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.PSIPHON_CHAIN_CDN));
        
        assertEquals("DNS bootstrap issue unresolved",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.PSIPHON_ONLY));
        
        assertEquals("Lifecycle validation incomplete",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.TOR_RELAY));
        
        assertEquals("Technical limitation (ordering)",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.EXIT_LOCATION));
        
        assertEquals("Not supported by Core",
                PrivacyCapabilities.getUnavailableReason(PrivacyCapabilities.PSIPHON_REVERSE));
    }
    
    @Test
    public void unknownCapabilityIsNotSupported() {
        assertFalse("Unknown capability should not be available",
                PrivacyCapabilities.isAvailable("unknown_feature"));
        assertEquals(PrivacyCapabilities.CapabilityStatus.NOT_SUPPORTED,
                PrivacyCapabilities.getStatus("unknown_feature"));
    }
}
