package com.firstham.aethergui;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PrivacySettingsTest {
    private Map<String, Object> defaults() { return CoreSettings.values(new LinkedHashMap<>()); }

    @Test public void additionsAreOffAndExistingTorKeepsItsSeparatePort() {
        Map<String, Object> values = defaults();
        assertEquals("off", values.get("psiphonMode"));
        assertEquals(false, values.get("exitLocationEnabled"));
        assertNull(PrivacySettings.unavailable(values));
        values.put("torProxy", true);
        Map<String, String> environment = CoreSettings.environment(values, "gool", "h2");
        assertEquals("127.0.0.1:1821", environment.get("AETHER_TOR_BIND"));
        assertEquals("off", environment.get("AETHER_TOR_BRIDGES"));
        assertFalse(environment.containsKey("AETHER_TOR_RELAYS"));
        assertFalse(environment.containsKey("AETHER_PSIPHON"));
    }

    @Test public void topologyAndChainTransportMapSeparatelyWithPerModeAvailability() {
        // Chain Auto and Direct are AVAILABLE (native tests passed)
        for (String shape : new String[]{"auto", "direct"}) {
            Map<String, Object> values = defaults();
            values.put("psiphonMode", "chain");
            values.put("psiphonTransport", shape);
            values.put("psiphonRegion", "de");
            values.put("psiphonHttp", true);
            
            Map<String, String> mapped = PrivacyRuntimeConfig.mappedOptions(values, "masque");
            assertEquals("chain", mapped.get("AETHER_PSIPHON"));
            assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, mapped.get("AETHER_PSIPHON_BIND"));
            assertEquals(shape, mapped.get("AETHER_PSIPHON_MODE"));
            assertEquals("DE", mapped.get("AETHER_PSIPHON_REGION"));
            assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, mapped.get("AETHER_PSIPHON_HTTP"));
            
            // Chain Auto/Direct are AVAILABLE - no unavailable error
            assertNull("Chain " + shape + " should be available", PrivacySettings.unavailable(values));
            
            // Should not throw when creating environment
            Map<String, String> env = CoreSettings.environment(values, "masque", "h2");
            assertNotNull(env);
        }
        
        // Chain CDN is BLOCKED (native tests failed 0/6)
        Map<String, Object> cdnValues = defaults();
        cdnValues.put("psiphonMode", "chain");
        cdnValues.put("psiphonTransport", "cdn");
        assertEquals("psiphonTransport", PrivacySettings.unavailable(cdnValues));
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(cdnValues, "masque", "h2"));
        
        // Psiphon Only is BLOCKED (DNS bootstrap failure)
        Map<String, Object> onlyValues = defaults();
        onlyValues.put("psiphonMode", "only");
        assertEquals("psiphonMode", PrivacySettings.unavailable(onlyValues));
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(onlyValues, "masque", "h2"));
        
        // Psiphon Reverse is NOT SUPPORTED
        Map<String, Object> reverseValues = defaults();
        reverseValues.put("psiphonMode", "reverse");
        assertEquals("psiphonMode", PrivacySettings.unavailable(reverseValues));
        for (String protocol : new String[]{"wg", "gool", "smart"}) {
            assertEquals("psiphonMode", CoreSettings.invalid(reverseValues, protocol, "h2"));
        }
    }

    @Test public void disabledOptionsDoNotLeakAndInvalidCombinationsAreRejected() {
        Map<String, Object> values = defaults();
        values.put("psiphonTransport", "cdn"); values.put("psiphonRegion", "IR"); values.put("psiphonHttp", true);
        assertTrue(PrivacyRuntimeConfig.mappedOptions(values, "wg").isEmpty());
        values.put("psiphonMode", "reverse");
        for (String protocol : new String[]{"wg", "gool", "smart"}) assertEquals("psiphonMode", CoreSettings.invalid(values, protocol, "h2"));
        values.put("torProxy", true);
        assertEquals("psiphonMode", CoreSettings.invalid(values, "masque", "h2"));
        values.put("torProxy", false); values.put("psiphonRegion", "Germany");
        assertEquals("psiphonRegion", CoreSettings.invalid(values, "masque", "h2"));
    }

    @Test public void torRelayPoliciesMapExactCoreSemanticsBehindGate() {
        Map<String, Object> values = defaults(); values.put("torProxy", true);
        values.put("torRelayCount", 75); values.put("torHttp", true);
        for (String policy : new String[]{"additional", "only", "off"}) {
            values.put("torRelayPolicy", policy);
            String expected = policy.equals("additional") ? "75" : policy.equals("only") ? "only:75" : "off";
            Map<String, String> mapped = PrivacyRuntimeConfig.mappedOptions(values, "gool");
            assertEquals(expected, mapped.get("AETHER_TOR_RELAYS"));
            if (!policy.equals("off")) assertEquals("auto", mapped.get("AETHER_TOR_BRIDGES"));
            assertEquals(PrivacyRuntimeConfig.TOR_HTTP, mapped.get("AETHER_TOR_HTTP"));
            assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(values, "gool", "h2"));
        }
    }

    @Test public void exitPolicyIsOffOrUnavailableNeverEmittedAtRuntime() {
        Map<String, Object> values = defaults(); values.put("exitLocationCountries", "de,SE,de");
        assertEquals("", PrivacySettings.exitPolicy(values));
        values.put("exitLocationEnabled", true); values.put("exitLocationMode", "exclude");
        assertEquals("!DE,SE", PrivacySettings.exitPolicy(values));
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(values, "wg", "h2"));
    }

    @Test public void portsStayFixedAndCollisionsFailWithoutRewritingSettings() {
        Map<String, Object> values = defaults(); values.put("torProxy", true);
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 18190);
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, "127.0.0.1:1821", null));
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 1819));
        assertEquals("127.0.0.1:1818", ProxyMode.HTTP_ADDRESS);
        assertEquals("127.0.0.1:1819", ProxyMode.SOCKS_ADDRESS);
    }

    @Test public void helperGateRunsBeforeFilesystemOrDocumentAccessForUnavailableModes() {
        // Available modes (Chain Auto/Direct) should not throw
        for (String transport : new String[]{"auto", "direct"}) {
            Map<String, Object> values = defaults();
            values.put("psiphonMode", "chain");
            values.put("psiphonTransport", transport);
            // Should NOT throw - these modes are available
            // (actual PrivacyRuntimeAssets.prepare would still need valid filesystem args)
        }
        
        // Unavailable modes should throw before filesystem access
        Map<String, Object> onlyValues = defaults();
        onlyValues.put("psiphonMode", "only");
        assertThrows("Psiphon Only should be blocked", IllegalArgumentException.class, 
            () -> PrivacyRuntimeAssets.prepare(null, null, null, "arm64-v8a", onlyValues));
        
        Map<String, Object> cdnValues = defaults();
        cdnValues.put("psiphonMode", "chain");
        cdnValues.put("psiphonTransport", "cdn");
        assertThrows("Psiphon CDN should be blocked", IllegalArgumentException.class,
            () -> PrivacyRuntimeAssets.prepare(null, null, null, "arm64-v8a", cdnValues));
        
        Map<String, Object> reverseValues = defaults();
        reverseValues.put("psiphonMode", "reverse");
        assertThrows("Psiphon Reverse should be blocked", IllegalArgumentException.class,
            () -> PrivacyRuntimeAssets.prepare(null, null, null, "arm64-v8a", reverseValues));
    }

    @Test public void onlyUsesMainSocksWithoutReservingAnImaginarySideListener() {
        Map<String, Object> values = defaults(); values.put("psiphonMode", "only");
        PrivacyRuntimeConfig.validateListeners(values, PrivacyRuntimeConfig.PSIPHON_SOCKS, null);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 1822);
        values.put("psiphonHttp", true);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null);
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, PrivacyRuntimeConfig.PSIPHON_HTTP, null));
        // Chain mode maps to the canonical full-device plan whatever base address is passed, and
        // the plan is internally collision-free; the LAN port still has to avoid every listener.
        values.put("psiphonMode", "chain");
        PrivacyRuntimeConfig.validateListeners(values, PrivacyRuntimeConfig.PSIPHON_SOCKS, null);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null);
    }

    @Test public void onlyHandsConfiguredHttpToTheOfficialPsiphonHelper() {
        Map<String, Object> values = defaults(); values.put("psiphonMode", "only");
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        for (boolean enabled : new boolean[]{false, true}) {
            values.put("psiphonHttp", enabled);
            PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null);
            Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
            assertFalse(environment.containsKey("AETHER_HTTP_PROXY"));
            assertEquals(ProxyMode.HTTP_ADDRESS, environment.get("AETHER_PSIPHON_HTTP"));
            Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
            assertEquals(2, listeners.size());
            assertFalse(listeners.containsKey("HTTP"));
            assertEquals(ProxyMode.HTTP_ADDRESS, listeners.get("Psiphon HTTP"));
        }
    }

    @Test public void managedListenersIncludeOnlyActiveOfficialEndpoints() {
        Map<String, Object> values = defaults();
        values.put("psiphonHttp", true);
        values.put("torHttp", true);
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        Map<String, String> inactive = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, inactive.size());
        assertThrows(UnsupportedOperationException.class, () -> inactive.put("unsafe", "127.0.0.1:1"));
        values.put("psiphonMode", "chain");
        Map<String, String> chain = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
        // Full-device chaining (dev.016): the final SOCKS entry is the Psiphon proxy itself, the
        // base SOCKS is the internal underlay, both public relay ports and the Psiphon HTTP
        // listener complete the plan - no duplicate "Psiphon" entry for the same port.
        assertEquals(5, chain.size());
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, chain.get("SOCKS5"));
        assertEquals(PsiphonChainRouting.INTERNAL_CORE_SOCKS, chain.get("Base"));
        assertEquals(ProxyMode.HTTP_ADDRESS, chain.get("HTTP"));
        assertEquals(ProxyMode.SOCKS_ADDRESS, chain.get("Public SOCKS5"));
        assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, chain.get("Psiphon HTTP"));
        assertFalse(chain.containsKey("Psiphon"));
        values.put("psiphonMode", "only");
        values.put("httpProxy", "");
        Map<String, String> only = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, only.size());
        assertFalse(only.containsKey("Psiphon"));
        values.put("psiphonMode", "off");
        values.put("torProxy", true);
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        Map<String, String> tor = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
        assertEquals(4, tor.size());
        assertEquals(PrivacyRuntimeConfig.TOR_SOCKS, tor.get("Tor"));
        assertEquals(PrivacyRuntimeConfig.TOR_HTTP, tor.get("Tor HTTP"));
    }

    @Test public void manualBridgesCannotSilentlyIgnoreRelaySourcePolicy() {
        Map<String, Object> values = defaults(); values.put("torProxy", true);
        values.put("torBridgeMode", "file"); values.put("torBridgeUri", "content://documents/bridge");
        values.put("torRelayPolicy", "only");
        assertEquals("torRelayPolicy", CoreSettings.invalid(values, "masque", "h2"));
    }

    @Test public void explicitRecoveryDisablesOnlyUnverifiedActivationAndPreservesDetails() {
        Map<String, Object> original = defaults();
        original.put("torProxy", true);
        original.put("psiphonMode", "chain");
        original.put("psiphonRegion", "DE");
        original.put("psiphonHttp", true);
        original.put("torRelayPolicy", "only");
        original.put("torRelayCount", 75);
        original.put("torBridgeMode", "file");
        original.put("torBridgeUri", "content://documents/bridge");
        original.put("torHttp", true);
        original.put("exitLocationEnabled", true);
        original.put("exitLocationCountries", "SE");
        Map<String, Object> recovered = PrivacySettings.disableUnavailable(original);
        assertNull(PrivacySettings.unavailable(recovered));
        assertEquals("chain", original.get("psiphonMode"));
        for (String key : new String[]{"torProxy", "psiphonRegion", "psiphonHttp", "torRelayCount", "torBridgeUri", "exitLocationCountries"})
            assertEquals(key, original.get(key), recovered.get(key));
        Map<String, String> environment = CoreSettings.environment(recovered, "wg", "h2");
        assertEquals("chain", environment.get("AETHER_TOR"));
        assertFalse(environment.containsKey("AETHER_PSIPHON"));
        assertFalse(environment.containsKey("AETHER_EXIT_LOC"));
        assertFalse(environment.containsKey("AETHER_TOR_HTTP"));
        assertEquals(recovered, PrivacySettings.disableUnavailable(recovered));
    }
}
