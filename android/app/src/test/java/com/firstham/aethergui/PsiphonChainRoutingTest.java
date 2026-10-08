package com.firstham.aethergui;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Regression coverage for the full-device Psiphon chain data plane (dev.016): public port
 * contract, internal base-listener ownership, routing selection, and the Psiphon-off behavior
 * that must not regress dev.015.
 */
public class PsiphonChainRoutingTest {
    private Map<String, Object> settings(String mode) {
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("psiphonMode", mode);
        return values;
    }

    @Test public void psiphonOffKeepsTheExactDev015DataPlane() {
        Map<String, Object> values = settings("off");
        values.put("httpProxy", "127.0.0.1:8080");
        assertEquals(ProxyMode.socksAddress("vpn", "127.0.0.1:1819"),
                PsiphonChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
        assertEquals(ProxyMode.socksAddress("vpn", "127.0.0.1:1819"),
                PsiphonChainRouting.coreSocksBind(values, "vpn", "127.0.0.1:1819"));
        Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        assertFalse(environment.containsKey("AETHER_PSIPHON"));
        assertEquals("127.0.0.1:8080", environment.get("AETHER_HTTP_PROXY"));
        Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, "127.0.0.1:1819");
        assertEquals("127.0.0.1:1819", listeners.get("SOCKS5"));
        assertEquals("127.0.0.1:8080", listeners.get("HTTP"));
        assertFalse(listeners.containsKey("Base"));
        assertFalse(listeners.containsKey("Psiphon"));
    }

    @Test public void chainSelectsThePsiphonFinalProxyAndMovesTheBaseListenerInside() {
        Map<String, Object> values = settings("chain");
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS,
                PsiphonChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
        assertEquals(PsiphonChainRouting.INTERNAL_CORE_SOCKS,
                PsiphonChainRouting.coreSocksBind(values, "vpn", "127.0.0.1:1819"));
        // The public contract keeps serving the same ports from the chain, not the base exit.
        Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, PrivacyRuntimeConfig.PSIPHON_SOCKS);
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, listeners.get("SOCKS5"));
        assertEquals(ProxyMode.HTTP_ADDRESS, listeners.get("HTTP"));
        assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, listeners.get("Psiphon HTTP"));
        assertFalse(listeners.containsKey("Psiphon"));
    }

    @Test public void chainAutoProvisionsTheInternalHttpListenerWithoutUserSettings() {
        for (boolean psiphonHttp : new boolean[]{false, true}) {
            Map<String, Object> values = settings("chain");
            values.put("psiphonHttp", psiphonHttp);
            Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
            assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, environment.get("AETHER_PSIPHON_HTTP"));
            assertFalse(environment.containsKey("AETHER_HTTP_PROXY"));
            // The saved flag is not rewritten by the runtime mapping.
            assertEquals(psiphonHttp, values.get("psiphonHttp"));
        }
    }

    @Test public void chainProbesOnlyTheFinalListenersAndNeverTheUnderlay() {
        Map<String, Object> values = settings("chain");
        values.put("psiphonHttp", false);
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(values, PrivacyRuntimeConfig.PSIPHON_SOCKS);
        assertEquals(2, endpoints.size());
        for (RuntimeTrafficPlan.Endpoint endpoint : endpoints) {
            assertFalse(PsiphonChainRouting.INTERNAL_CORE_SOCKS.equals(endpoint.address));
            assertFalse(ProxyMode.HTTP_ADDRESS.equals(endpoint.address));
            assertTrue(endpoint.privacy);
        }
    }

    @Test public void blockedTopologiesNeverGetTheChainDataPlane() {
        for (String topology : new String[]{"off", "only", "reverse"}) {
            Map<String, Object> values = settings(topology);
            assertEquals(ProxyMode.socksAddress("vpn", "127.0.0.1:1819"),
                    PsiphonChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
            Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, "127.0.0.1:1819");
            assertFalse(listeners.containsKey("Base"));
            assertEquals("127.0.0.1:1819", listeners.get("SOCKS5"));
        }
    }

    @Test public void internalListenerPlanStaysCollisionFreeInChainMode() {
        Map<String, Object> values = settings("chain");
        // The canonical plan is internally collision-free whatever base address is passed.
        PrivacyRuntimeConfig.validateListeners(values, PrivacyRuntimeConfig.PSIPHON_SOCKS, null);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 18190);
        for (int occupied : new int[]{1822, 1824, 18193, 1818, 1819}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PrivacyRuntimeConfig.validateListeners(values, PrivacyRuntimeConfig.PSIPHON_SOCKS, occupied));
        }
    }
}
