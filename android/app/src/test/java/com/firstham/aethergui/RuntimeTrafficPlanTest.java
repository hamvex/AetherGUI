package com.firstham.aethergui;

import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class RuntimeTrafficPlanTest {
    @Test public void baseVpnHasNoExtraPrivacyTraffic() {
        Map<String, Object> settings = new LinkedHashMap<>();
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, "127.0.0.1:8019");
        assertEquals(1, endpoints.size());
        assertTrue(endpoints.get(0).primary);
        assertFalse(endpoints.get(0).http);
        assertFalse(endpoints.get(0).privacy);
        assertFalse(RuntimeTrafficPlan.privacyEnabled(settings));
        assertTrue(new RuntimeTrafficPlan.Proof(endpoints).record(endpoints.get(0)));
    }

    @Test public void dualProxyKeepsFixedPortsAndNeedsBothProtocols() {
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("httpProxy", "127.0.0.1:8080");
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(ProxyMode.settings("manual", saved), ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, endpoints.size());
        assertEquals("127.0.0.1:1819", endpoints.get(0).address);
        assertEquals("127.0.0.1:1818", endpoints.get(1).address);
        assertTrue(endpoints.get(1).http);
        assertEquals("127.0.0.1:8080", saved.get("httpProxy"));
        RuntimeTrafficPlan.Proof proof = new RuntimeTrafficPlan.Proof(endpoints);
        assertFalse(proof.record(endpoints.get(0)));
        assertFalse(proof.record(endpoints.get(0)));
        assertTrue(proof.record(endpoints.get(1)));
    }

    @Test public void torTrafficCannotBeProvenByTheWarpExit() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("torProxy", true);
        settings.put("torHttp", true);
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS);
        assertEquals(3, endpoints.size());
        assertEquals(PrivacyRuntimeConfig.TOR_SOCKS, endpoints.get(1).address);
        assertEquals(PrivacyRuntimeConfig.TOR_HTTP, endpoints.get(2).address);
        assertTrue(endpoints.get(1).privacy);
        assertFalse(endpoints.get(1).primary);
        assertTrue(endpoints.get(2).http);
        assertTrue(RuntimeTrafficPlan.privacyEnabled(settings));
        RuntimeTrafficPlan.Proof proof = new RuntimeTrafficPlan.Proof(endpoints);
        assertFalse(proof.record(endpoints.get(0)));
        assertFalse(proof.record(endpoints.get(1)));
        assertTrue(proof.record(endpoints.get(2)));
    }

    @Test public void chainNeedsSeparateSocksAndHttpProofsForEveryTransport() {
        for (String transport : PsiphonConfiguration.CHAIN_TRANSPORTS) {
            Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
            settings.put("psiphonMode", "chain");
            settings.put("psiphonTransport", transport);
            settings.put("psiphonHttp", true);
            // dev.017 CHANGE 5: the full-device chains are a Device VPN feature; Proxy mode
            // deactivates them (see proxyModeDeactivatesSavedChains), so the plan is built from
            // the plain saved settings here.
            List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS);
            // Full-device chaining: the plan is exactly the two final Psiphon listeners. The
            // internal base underlay is never a probe target, the public HTTP relay is proven
            // through the listener it forwards to, and the SOCKS5 entry is the Psiphon final
            // proxy itself, so both endpoints are announcement-gated privacy endpoints.
            assertEquals(2, endpoints.size());
            assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, endpoints.get(0).address);
            assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, endpoints.get(1).address);
            assertTrue(endpoints.get(0).privacy);
            assertTrue(endpoints.get(1).privacy);
            assertTrue(endpoints.get(1).http);
            RuntimeTrafficPlan.Proof proof = new RuntimeTrafficPlan.Proof(endpoints);
            assertFalse(proof.record(endpoints.get(0)));
            assertTrue(proof.record(endpoints.get(1)));
        }
    }

    @Test public void proxyModeDeactivatesSavedChainsWithoutRewritingStorage() {
        // dev.017 CHANGE 5: in Proxy mode the base protocol serves the public 1818/1819 endpoints
        // (the dev.016 Psiphon-OFF proxy contract) whatever chain was saved for Device VPN; the
        // saved map itself is never rewritten, so the chain runs again after switching back.
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("psiphonMode", "chain");
        saved.put("psiphonTransport", "direct");
        saved.put("psiphonRegion", "DE");
        Map<String, Object> effective = ProxyMode.settings("manual", saved);
        assertEquals("off", effective.get("psiphonMode"));
        assertEquals("side", effective.get("torMode"));
        assertEquals("chain", saved.get("psiphonMode"));
        assertEquals("direct", saved.get("psiphonTransport"));
        assertEquals("DE", saved.get("psiphonRegion"));
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(effective, ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, endpoints.size());
        assertEquals(ProxyMode.SOCKS_ADDRESS, endpoints.get(0).address);
        assertEquals(ProxyMode.HTTP_ADDRESS, endpoints.get(1).address);
        // ...and the same saved settings chain again in Device VPN mode.
        List<RuntimeTrafficPlan.Endpoint> vpnEndpoints = RuntimeTrafficPlan.endpoints(saved, "127.0.0.1:1819");
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, vpnEndpoints.get(0).address);
    }

    @Test public void onlyUsesPublicPrivacyPortsWithoutWarpProof() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("psiphonMode", "only");
        settings.put("psiphonTransport", "direct");
        // Proxy mode deactivates the full-device chains (CHANGE 5); Only keeps the configured
        // public HTTP address, so the plan is built from the plain saved settings with the
        // public HTTP proxy configured.
        settings.put("httpProxy", ProxyMode.HTTP_ADDRESS);
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, endpoints.size());
        assertTrue(endpoints.get(0).privacy);
        assertTrue(endpoints.get(1).privacy);
        assertTrue(endpoints.get(0).primary);
        assertFalse(endpoints.get(1).primary);
        assertEquals(ProxyMode.HTTP_ADDRESS, endpoints.get(1).address);
    }

    @Test public void inactiveSavedOptionsDoNotAddProbes() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("psiphonHttp", true);
        settings.put("psiphonTransport", "cdn");
        settings.put("torHttp", true);
        assertEquals(1, RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS).size());
        assertFalse(RuntimeTrafficPlan.privacyEnabled(settings));
    }

    @Test public void configuredVpnHttpAlsoRequiresTraffic() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("httpProxy", "127.0.0.1:8080");
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS);
        assertEquals(2, endpoints.size());
        assertEquals("127.0.0.1:8080", endpoints.get(1).address);
        assertTrue(endpoints.get(1).http);
    }

    @Test public void proofsAndEndpointsCannotLeakAcrossReconnects() {
        List<RuntimeTrafficPlan.Endpoint> first = RuntimeTrafficPlan.endpoints(Collections.emptyMap(), ProxyMode.SOCKS_ADDRESS);
        List<RuntimeTrafficPlan.Endpoint> second = RuntimeTrafficPlan.endpoints(Collections.emptyMap(), ProxyMode.SOCKS_ADDRESS);
        RuntimeTrafficPlan.Proof proof = new RuntimeTrafficPlan.Proof(second);
        assertThrows(IllegalArgumentException.class, () -> proof.record(first.get(0)));
        assertTrue(proof.record(second.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> second.clear());
        assertThrows(IllegalArgumentException.class, () -> new RuntimeTrafficPlan.Proof(Collections.emptyList()));
    }

    @Test public void conflictingPortsFailBeforeAnyProof() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("torProxy", true);
        assertThrows(IllegalArgumentException.class, () -> RuntimeTrafficPlan.endpoints(settings, PrivacyRuntimeConfig.TOR_SOCKS));
    }

    @Test public void chainAnnouncementsCannotBeSubstitutedByWarpOrAnotherAddress() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("psiphonMode", "chain");
        settings.put("psiphonHttp", true);
        List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(settings, ProxyMode.SOCKS_ADDRESS);
        // Full-device chaining: endpoints are exactly the Psiphon final SOCKS (index 0) and the
        // Psiphon HTTP listener (index 1); the final SOCKS announcement cannot be satisfied by
        // the WARP base line, another address, or a mismatched suffix.
        assertEquals(2, endpoints.size());
        RuntimeTrafficPlan.Announcements state = new RuntimeTrafficPlan.Announcements(settings, ProxyMode.SOCKS_ADDRESS);
        state.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        state.onCoreLog("[+] psiphon is ready; 127.0.0.1:1823 leaves through psiphon, carried by the tunnel");
        state.onCoreLog("[+] psiphon is ready; 127.0.0.1:1822 leaves through psiphon");
        assertFalse(state.announced(endpoints.get(0)));
        state.onCoreLog("[+] psiphon is ready; 127.0.0.1:1822 leaves through psiphon, carried by the tunnel");
        assertTrue(state.announced(endpoints.get(0)));
        assertFalse(state.allAnnounced());
        state.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1824");
        assertTrue(state.allAnnounced());
        state.onCoreLog("[-] psiphon: psiphon stopped: exit status: 1");
        assertFalse(state.allAnnounced());
        assertFalse(state.announced(endpoints.get(0)));
    }

    @Test public void onlyRequiresItsOwnPublicListenerAnnouncements() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("psiphonMode", "only");
        // Proxy mode deactivates the privacy chains (CHANGE 5); the announcement contract is
        // exercised with the plain saved settings.
        RuntimeTrafficPlan.Announcements state = new RuntimeTrafficPlan.Announcements(settings, ProxyMode.SOCKS_ADDRESS);
        state.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        state.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertFalse(state.allAnnounced());
        state.onCoreLog("[+] psiphon is ready; 127.0.0.1:1819 leaves through psiphon");
        state.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1818");
        assertTrue(state.allAnnounced());
        assertFalse(new RuntimeTrafficPlan.Announcements(settings, ProxyMode.SOCKS_ADDRESS).allAnnounced());
    }

    @Test public void torNeedsBootstrapAndSeparateHttpAnnouncements() {
        Map<String, Object> settings = CoreSettings.values(new LinkedHashMap<>());
        settings.put("torProxy", true);
        settings.put("torHttp", true);
        RuntimeTrafficPlan.Announcements state = new RuntimeTrafficPlan.Announcements(settings, ProxyMode.SOCKS_ADDRESS);
        state.onCoreLog("[+] tor socks5 listening on 127.0.0.1:1821");
        assertFalse(state.allAnnounced());
        state.onCoreLog("[+] tor is ready; 127.0.0.1:1821 leaves through tor, carried by the tunnel");
        assertFalse(state.allAnnounced());
        state.onCoreLog("[+] tor http proxy listening on 127.0.0.1:1825");
        assertTrue(state.allAnnounced());
        state.onCoreLog("[-] the tor http proxy stopped: example failure");
        assertFalse(state.allAnnounced());
        assertTrue(new RuntimeTrafficPlan.Announcements(CoreSettings.values(new LinkedHashMap<>()), ProxyMode.SOCKS_ADDRESS).allAnnounced());
    }
}
