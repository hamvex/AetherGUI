package com.firstham.aethergui;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class CoreSettingsTest {
    private Map<String, Object> settings() { return new HashMap<>(CoreSettings.DEFAULTS); }
    @Test public void inheritedExitPolicyAndPsiphonCannotLeakIntoAnyProtocol() {
        for (String protocol : new String[]{"wg", "gool", "masque", "mim"}) {
            Map<String, String> environment = new HashMap<>();
            environment.put("AETHER_EXIT_LOC", "!IR");
            environment.put("AETHER_EXIT_LOC_SECS", "1");
            environment.put("AETHER_PSIPHON", "only");
            environment.put("AETHER_PSIPHON_CONFIG", "/unapproved.json");
            environment.put("AETHER_MARK", "123");
            environment.put("RUST_LOG", "trace");
            environment.put("SSL_CERT_FILE", "/unapproved.pem");
            environment.put("SSL_CERT_DIR", "/unapproved-roots");
            environment.put("PATH", "retained");
            CoreSettings.clearInheritedEnvironment(environment);
            Map<String, Object> preferences = settings();
            preferences.put("AETHER_EXIT_LOC", "!IR");
            preferences.put("exitCountry", "DE");
            preferences.put("psiphon", true);
            environment.putAll(CoreSettings.environment(preferences, protocol, "h2"));
            assertEquals("retained", environment.get("PATH"));
            assertFalse(environment.containsKey("RUST_LOG"));
            assertFalse(environment.containsKey("SSL_CERT_FILE"));
            assertFalse(environment.containsKey("SSL_CERT_DIR"));
            assertFalse(environment.containsKey("AETHER_MARK"));
            assertTrue(environment.keySet().stream().noneMatch(key -> key.startsWith("AETHER_EXIT_LOC") || key.startsWith("AETHER_PSIPHON")));
            assertFalse(CoreSettings.DEFAULTS.containsKey("exitCountry"));
            assertFalse(CoreSettings.DEFAULTS.containsKey("psiphon"));
        }
    }

    @Test public void h2FragmentationInheritsTheCoreSniSplitDefault() {
        Map<String, Object> preferences = settings();
        preferences.put("h2Fragment", true);
        Map<String, String> environment = CoreSettings.environment(preferences, "masque", "h2");
        assertEquals("1", environment.get("AETHER_MASQUE_H2_FRAGMENT"));
        assertFalse(environment.containsKey("AETHER_MASQUE_H2_FRAGMENT_SNI"));
    }

    @Test public void defaultsPreserveValidationAndProfileRetry() {
        Map<String, String> e = CoreSettings.environment(settings(), "gool", "h2");
        assertFalse(e.containsKey("AETHER_WG_NO_DATA_CHECK"));
        assertFalse(e.containsKey("AETHER_MASQUE_NO_DATA_CHECK"));
        assertFalse(e.containsKey("AETHER_WG_NO_PROFILE_RETRY"));
        assertFalse(e.containsKey("AETHER_ECH"));
        assertFalse(e.containsKey("AETHER_TOR"));
        assertEquals("5", e.get("AETHER_WG_KEEPALIVE"));
        assertEquals("400", e.get("AETHER_ROUTE_SNIFF_MS"));
    }
    @Test public void presenceFlagsOnlyAppearWhenEnabled() {
        Map<String, Object> v = settings(); v.put("noDataCheck", true); v.put("wgNoProfileRetry", true);
        Map<String, String> e = CoreSettings.environment(v, "wg", "h3");
        assertEquals("1", e.get("AETHER_WG_NO_DATA_CHECK")); assertEquals("1", e.get("AETHER_MASQUE_NO_DATA_CHECK"));
        assertEquals("1", e.get("AETHER_WG_NO_PROFILE_RETRY"));
    }
    @Test public void masqueInMasqueUsesItsOwnEndpointsAndTransportOptions() {
        Map<String, Object> v = settings(); v.put("mimOuterPeer", "162.159.192.1:443"); v.put("mimInnerPeer", "188.114.96.1:443");
        v.put("wiwOuterPeer", "162.159.192.2:2408"); v.put("h2Fragment", true); v.put("ech", "auto");
        Map<String, String> e = CoreSettings.environment(v, "mim", "h2");
        assertEquals("188.114.96.1:443", e.get("AETHER_MIM_INNER_PEER")); assertFalse(e.containsKey("AETHER_WIW_OUTER_PEER"));
        assertEquals("1", e.get("AETHER_MASQUE_H2_FRAGMENT")); assertEquals("auto", e.get("AETHER_ECH"));
        assertFalse(CoreSettings.environment(v, "mim", "h3").containsKey("AETHER_MASQUE_H2_FRAGMENT"));
    }
    @Test public void protocolSwitchKeepsIrrelevantEnvironmentAbsent() {
        Map<String, Object> v = settings(); v.put("ech", "auto"); v.put("h2Fragment", true); v.put("mimOuterPeer", "162.159.192.1:443");
        Map<String, String> e = CoreSettings.environment(v, "wg", "h2");
        assertFalse(e.containsKey("AETHER_ECH")); assertFalse(e.containsKey("AETHER_MASQUE_H2_FRAGMENT")); assertFalse(e.containsKey("AETHER_MIM_OUTER_PEER"));
    }
    @Test public void rejectsUnsafeNumbersAndMalformedTypes() {
        for (String key : new String[]{"validateSecs", "startupSecs", "reconnectSecs", "routeSniffMs", "wgKeepalive"}) {
            Map<String, Object> v = settings(); v.put(key, 0); assertEquals(key, CoreSettings.invalid(v, "masque", "h2"));
            v.put(key, Integer.MAX_VALUE); assertEquals(key, CoreSettings.invalid(v, "masque", "h2"));
            v.put(key, "10"); assertEquals(key, CoreSettings.invalid(v, "masque", "h2"));
        }
    }
    @Test public void fragmentsRequireBoundedAscendingRanges() {
        assertTrue(CoreSettings.range("16-32", 1, 16384)); assertTrue(CoreSettings.range("0", 0, 100));
        for (String value : new String[]{"32-16", "-1", "1-2-3", "9999999999999999999", "x", "0"}) assertFalse(value, CoreSettings.range(value, 1, 16384));
    }
    @Test public void validatesLiteralEndpointsAndDistinctHopAddresses() {
        assertTrue(CoreSettings.endpoint("[2606:4700::1]:443", false));
        assertFalse(CoreSettings.endpoint("example.com:443", false)); assertFalse(CoreSettings.endpoint("1.2.3.4:70000", false));
        assertFalse(CoreSettings.endpoint("2606:4700::1:443", false));
        Map<String, Object> v = settings(); v.put("wiwOuterPeer", "[2606:4700::1]:443"); v.put("wiwInnerPeer", "[2606:4700:0:0:0:0:0:1]:2408");
        assertEquals("wiwInnerPeer", CoreSettings.invalid(v, "gool", "h2"));
    }
    @Test public void rejectsHttpsInsteadOfSilentlyUsingPlainHttp() {
        assertTrue(CoreSettings.upstream("socks5://user:pass@127.0.0.1:1080")); assertTrue(CoreSettings.upstream("http://proxy.example:8080"));
        for (String uri : new String[]{"https://proxy.example:443", "https://user:pass@proxy.example:443", "http://proxy.example", "file:///tmp", "http://host:8080/path", "http://host:8080?x=1"}) assertFalse(uri, CoreSettings.upstream(uri));
    }
    @Test public void teamRequiresHeadlessCredentialsAndNeverPassesEmailOtp() {
        Map<String, Object> v = settings(); v.put("team", "example"); v.put("accessEmail", "test@example.test");
        assertEquals("accessToken", CoreSettings.invalid(v, "wg", "h2"));
        v.put("accessClientId", "client"); assertEquals("accessClientSecret", CoreSettings.invalid(v, "wg", "h2"));
        v.put("accessClientSecret", "secret"); v.put("gateway", true);
        Map<String, String> e = CoreSettings.environment(v, "wg", "h2");
        assertEquals("client", e.get("AETHER_ACCESS_CLIENT_ID")); assertEquals("secret", e.get("AETHER_ACCESS_CLIENT_SECRET"));
        assertEquals("1", e.get("AETHER_GATEWAY")); assertFalse(e.containsKey("AETHER_ACCESS_EMAIL"));
        v.put("gateway", false);
        assertFalse(CoreSettings.environment(v, "wg", "h2").containsKey("AETHER_GATEWAY"));
    }
    @Test public void validatesDnsAndLoopbackListener() {
        Map<String, Object> v = settings(); v.put("dns", "9.9.9.9,2606:4700:4700::1111"); v.put("httpProxy", "127.0.0.1:1820");
        assertNull(CoreSettings.invalid(v, "masque", "h2"));
        assertEquals("9.9.9.9,2606:4700:4700::1111", CoreSettings.environment(v, "masque", "h2").get("AETHER_DNS"));
        v.put("httpProxy", "0.0.0.0:1820"); assertEquals("httpProxy", CoreSettings.invalid(v, "masque", "h2"));
        v.put("httpProxy", ""); v.put("dns", "resolver.example"); assertEquals("dns", CoreSettings.invalid(v, "masque", "h2"));
    }
    @Test public void routesRejectUnsupportedSyntaxInsteadOfLosingRules() {
        assertTrue(CoreSettings.rules("example.com\nfull:ads.example\nkeyword:ads\n10.0.0.0/8\ncidr:fd00::/8\nip:fd00::1\nport:25\nprivate"));
        assertFalse(CoreSettings.rules("regexp:(?=unsupported)")); assertFalse(CoreSettings.rules("fd00::/8"));
        assertFalse(CoreSettings.rules("10.0.0.0/99")); assertFalse(CoreSettings.rules("port:65536")); assertFalse(CoreSettings.rules("ip:example.com"));
    }
    @Test public void torUsesASeparateProxyWithoutChangingMainProtocol() {
        Map<String, Object> v = settings(); v.put("torProxy", true);
        Map<String, String> e = CoreSettings.environment(v, "gool", "h2");
        assertEquals("chain", e.get("AETHER_TOR")); assertEquals("127.0.0.1:1821", e.get("AETHER_TOR_BIND"));
        assertEquals("off", e.get("AETHER_TOR_BRIDGES")); assertFalse(e.containsKey("AETHER_SOCKS"));
    }
    @Test public void redactCredentialsBeforeLogsAreStored() {
        Map<String, Object> v = settings(); v.put("accessToken", "sensitive-token"); v.put("upstreamProxy", "socks5://alice:p%40ss@localhost:1080");
        String log = CoreSettings.redact("sensitive-token socks5://alice:p%40ss@localhost:1080 p@ss", v);
        assertFalse(log.contains("sensitive-token")); assertFalse(log.contains("alice")); assertFalse(log.contains("p@ss"));
    }
    @Test public void environmentRejectsControlCharactersWithoutDisclosingValues() {
        Map<String, Object> v = settings(); v.put("accessToken", "secret\nother");
        try { CoreSettings.environment(v, "wg", "h2"); fail(); }
        catch (IllegalArgumentException e) { assertFalse(e.getMessage().contains("secret")); }
    }
}
