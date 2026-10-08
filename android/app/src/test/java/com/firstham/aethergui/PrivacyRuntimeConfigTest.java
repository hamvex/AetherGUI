package com.firstham.aethergui;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PrivacyRuntimeConfigTest {
    @Test public void publicProxyOnlyOwnsBothFixedPortsWithoutChangingVpnPreferences() {
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("psiphonMode", "only");
        saved.put("psiphonTransport", "cdn");
        saved.put("httpProxy", "127.0.0.1:8080");
        Map<String, Object> original = new LinkedHashMap<>(saved);
        for (boolean enabled : new boolean[]{false, true}) {
            saved.put("psiphonHttp", enabled);
            Map<String, Object> effective = ProxyMode.settings("manual", saved);
            Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(effective, "masque");
            Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(effective,
                    ProxyMode.socksAddress("manual", "127.0.0.1:8081"));
            assertEquals("direct", environment.get("AETHER_PSIPHON"));
            assertEquals(ProxyMode.HTTP_ADDRESS, environment.get("AETHER_PSIPHON_HTTP"));
            assertFalse(environment.containsKey("AETHER_HTTP_PROXY"));
            assertFalse(environment.containsKey("AETHER_PSIPHON_BIND"));
            assertFalse(environment.containsKey("AETHER_PSIPHON_MODE"));
            assertEquals(2, listeners.size());
            assertEquals(ProxyMode.SOCKS_ADDRESS, listeners.get("SOCKS5"));
            assertEquals(ProxyMode.HTTP_ADDRESS, listeners.get("Psiphon HTTP"));
            assertEquals(original.get("httpProxy"), saved.get("httpProxy"));
            assertEquals(original.get("psiphonTransport"), saved.get("psiphonTransport"));
            assertEquals(saved, ProxyMode.settings("vpn", saved));
            assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(effective, "masque", "h2"));
        }
    }

    @Test public void everyHttpAnnouncementMatchesTheEmittedOwnerAndAddress() {
        for (String topology : Arrays.asList("off", "only", "chain", "reverse")) {
            for (String configured : Arrays.asList("", ProxyMode.HTTP_ADDRESS, "[::1]:8080")) {
                for (boolean enabled : new boolean[]{false, true}) {
                    Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
                    values.put("psiphonMode", topology);
                    values.put("httpProxy", configured);
                    values.put("psiphonHttp", enabled);
                    Map<String, Object> original = new LinkedHashMap<>(values);
                    Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "masque");
                    Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
                    // In Chain mode the base HTTP listener is deliberately not started (the
                    // public 1818 contract is served by the app relay in front of the Psiphon
                    // HTTP listener), and that final listener is auto-provisioned whatever the
                    // saved psiphonHttp setting says.
                    if ("chain".equals(topology)) {
                        assertFalse(environment.containsKey("AETHER_HTTP_PROXY"));
                        assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, environment.get("AETHER_PSIPHON_HTTP"));
                        assertEquals(ProxyMode.HTTP_ADDRESS, listeners.get("HTTP"));
                        assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, listeners.get("Psiphon HTTP"));
                        assertEquals(PsiphonChainRouting.INTERNAL_CORE_SOCKS, listeners.get("Base"));
                        assertEquals(ProxyMode.SOCKS_ADDRESS, listeners.get("Public SOCKS5"));
                    } else {
                        assertEquals(environment.get("AETHER_HTTP_PROXY"), listeners.get("HTTP"));
                        assertEquals(environment.get("AETHER_PSIPHON_HTTP"), listeners.get("Psiphon HTTP"));
                    }
                    if (topology.equals("only")) {
                        assertFalse(environment.containsKey("AETHER_HTTP_PROXY"));
                        assertFalse(listeners.containsKey("Psiphon"));
                    }
                    if (topology.equals("off")) assertFalse(environment.containsKey("AETHER_PSIPHON_HTTP"));
                    PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 18190);
                    assertEquals(original, values);
                }
            }
        }
    }

    @Test public void onlyReservesTheEffectiveHttpPortNotAnUnusedSidePort() {
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("psiphonMode", "only");
        values.put("psiphonHttp", true);
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 1824);
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 1818));
        values.put("httpProxy", ProxyMode.SOCKS_ADDRESS);
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null));
        values.put("httpProxy", "");
        assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, 1824));
        // Chain mode: the canonical full-device plan is internally collision-free and ignores
        // the caller's base address, but a LAN port landing on any plan listener - the final
        // Psiphon SOCKS, the internal base underlay, the public relay ports or the Psiphon
        // HTTP listener - must be rejected rather than silently overlapped.
        values.put("psiphonMode", "chain");
        values.put("httpProxy", PrivacyRuntimeConfig.PSIPHON_HTTP);
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null);
        values.put("httpProxy", "");
        PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, null);
        for (int occupied : new int[]{1822, 1824, 18193, 1818, 1819}) {
            assertThrows(IllegalArgumentException.class, () -> PrivacyRuntimeConfig.validateListeners(values, ProxyMode.SOCKS_ADDRESS, occupied));
        }
    }

    @Test public void disablingOnlyRestoresBaseHttpAndKeepsChainHttpSeparate() {
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("httpProxy", "127.0.0.1:8080");
        values.put("psiphonHttp", true);
        values.put("psiphonMode", "only");
        assertEquals("127.0.0.1:8080", PrivacyRuntimeConfig.mappedOptions(values, "wg").get("AETHER_PSIPHON_HTTP"));
        values.put("psiphonMode", "chain");
        Map<String, String> chain = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        // Chain is the final device egress: the base HTTP listener is not started at all, and
        // the Psiphon HTTP listener is auto-provisioned on its internal address to serve the
        // public 1818 contract through the app relay. The saved httpProxy setting is untouched.
        assertFalse(chain.containsKey("AETHER_HTTP_PROXY"));
        assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, chain.get("AETHER_PSIPHON_HTTP"));
        values.put("psiphonMode", "off");
        Map<String, String> off = CoreSettings.environment(values, "wg", "h2");
        assertEquals("127.0.0.1:8080", off.get("AETHER_HTTP_PROXY"));
        assertFalse(off.containsKey("AETHER_PSIPHON_HTTP"));
    }

    @Test public void torHttpKeepsItsOwnListenerAndItsActivationGate() {
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        values.put("torProxy", true);
        values.put("torHttp", true);
        Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, ProxyMode.SOCKS_ADDRESS);
        assertEquals(4, listeners.size());
        assertEquals(ProxyMode.HTTP_ADDRESS, environment.get("AETHER_HTTP_PROXY"));
        assertEquals(PrivacyRuntimeConfig.TOR_HTTP, environment.get("AETHER_TOR_HTTP"));
        assertEquals(environment.get("AETHER_TOR_HTTP"), listeners.get("Tor HTTP"));
        assertFalse(environment.containsKey("AETHER_PSIPHON_HTTP"));
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(values, "wg", "h2"));
    }
}
