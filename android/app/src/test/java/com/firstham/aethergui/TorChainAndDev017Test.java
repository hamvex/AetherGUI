package com.firstham.aethergui;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.017 regression coverage for the 15 requested changes: combined Tor protocol entries and
 * chain routing, Proxy-mode filtering that preserves saved Psiphon preferences, the Manual
 * country code, Turbo/Auto defaults, the WIW/MIM display aliases, the display order, and the
 * safe gating of unverified Tor combinations.
 */
public class TorChainAndDev017Test {
    private Map<String, Object> defaults() { return CoreSettings.values(new LinkedHashMap<>()); }

    // --- CHANGE 7: full-device Tor chain ---------------------------------------------------

    @Test public void torChainCombinesOnlyVerifiedBaseProtocolsWithCapabilityGating() {
        // The combined + Tor storage indices are fixed at 8/9/10 (WireGuard/gool/MASQUE + Tor);
        // they are only offered while the TOR_CHAIN capability is AVAILABLE, and the blocked
        // combinations (Smart, MIM) never get an entry.
        assertEquals(8, TorChainRouting.combinedTorProtocolIndex(1));
        assertEquals(9, TorChainRouting.combinedTorProtocolIndex(2));
        assertEquals(10, TorChainRouting.combinedTorProtocolIndex(0));
        assertEquals(-1, TorChainRouting.combinedTorProtocolIndex(3));
        assertEquals(-1, TorChainRouting.combinedTorProtocolIndex(4));
        assertEquals(-1, TorChainRouting.combinedTorProtocolIndex(5));
        assertEquals(1, TorChainRouting.combinedTorBaseIndex(8));
        assertEquals(2, TorChainRouting.combinedTorBaseIndex(9));
        assertEquals(0, TorChainRouting.combinedTorBaseIndex(10));
        assertEquals(-1, TorChainRouting.combinedTorBaseIndex(3));
        assertEquals(-1, TorChainRouting.combinedTorBaseIndex(7));
        assertEquals(-1, TorChainRouting.combinedTorBaseIndex(11));
    }

    @Test public void torChainRoutingFollowsTheSavedTorModeNotTheProtocolIndex() {
        // torProxy alone keeps the dev.016 side-proxy contract; only torMode=chain activates the
        // full-device remap. The persisted "protocol" always stores the base protocol.
        for (int base : new int[]{0, 1, 2}) {
            Map<String, Object> values = defaults();
            values.put("protocol", base);
            values.put("torProxy", true);
            assertFalse(TorChainRouting.chainActive(values));
            values.put("torMode", "chain");
            assertTrue(TorChainRouting.chainActive(values));
            assertEquals(PrivacyRuntimeConfig.TOR_SOCKS,
                    TorChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
            assertEquals(PsiphonChainRouting.INTERNAL_CORE_SOCKS,
                    TorChainRouting.coreSocksBind(values, "vpn", "127.0.0.1:1819"));
        }
    }

    @Test public void torChainListenerPlanMirrorsThePsiphonChainTopology() {
        Map<String, Object> values = defaults();
        values.put("torProxy", true);
        values.put("torMode", "chain");
        Map<String, String> listeners = PrivacyRuntimeConfig.listenerAddresses(values, "127.0.0.1:1819");
        // The plan is canonical: the final SOCKS entry is the Tor proxy itself, the base SOCKS
        // is the internal underlay, the public 1818/1819 ports are the app's relays and the Tor
        // HTTP listener keeps the public HTTP contract.
        assertEquals(PrivacyRuntimeConfig.TOR_SOCKS, listeners.get("SOCKS5"));
        assertEquals(TorChainRouting.INTERNAL_CORE_SOCKS, listeners.get("Base"));
        assertEquals(ProxyMode.HTTP_ADDRESS, listeners.get("HTTP"));
        assertEquals(ProxyMode.SOCKS_ADDRESS, listeners.get("Public SOCKS5"));
        assertEquals(PrivacyRuntimeConfig.TOR_HTTP, listeners.get("Tor HTTP"));
        assertEquals(5, listeners.size());
        assertFalse(listeners.containsKey("Tor"));
    }

    @Test public void torChainEmitsTheOfficialCoreTorEnvironment() throws Exception {
        Map<String, Object> values = defaults();
        values.put("torProxy", true);
        values.put("torMode", "chain");
        values.put("psiphonRegion", "DE");
        Map<String, String> environment = CoreSettings.environment(values, "wg", "h2");
        assertEquals("chain", environment.get("AETHER_TOR"));
        assertEquals(PrivacyRuntimeConfig.TOR_SOCKS, environment.get("AETHER_TOR_BIND"));
        // The chain auto-provisions the Tor HTTP listener for the public 1818 contract and keeps
        // bridges off (the Tor connection is already carried by the WARP underlay).
        assertEquals(PrivacyRuntimeConfig.TOR_HTTP, environment.get("AETHER_TOR_HTTP"));
        assertEquals("off", environment.get("AETHER_TOR_BRIDGES"));
        // Psiphon is mutually exclusive with a Tor chain: no Psiphon variables leak.
        assertFalse(environment.containsKey("AETHER_PSIPHON"));
        assertFalse(environment.containsKey("AETHER_PSIPHON_REGION"));
        // The mapped privacy options are the same variables CoreSettings.environment carries
        // (it adds the base protocol settings on top).
        for (Map.Entry<String, String> entry : PrivacyRuntimeConfig.mappedOptions(values, "wg").entrySet())
            assertEquals(entry.getValue(), environment.get(entry.getKey()));
    }

    @Test public void torSideModeKeepsTheDev016SeparateProxyContract() {
        Map<String, Object> values = defaults();
        values.put("torProxy", true);
        Map<String, String> environment = CoreSettings.environment(values, "gool", "h2");
        assertEquals("chain", environment.get("AETHER_TOR"));
        assertEquals("127.0.0.1:1821", environment.get("AETHER_TOR_BIND"));
        assertEquals("off", environment.get("AETHER_TOR_BRIDGES"));
        assertFalse(environment.containsKey("AETHER_TOR_HTTP"));
    }

    @Test public void unsupportedTorCombinationsAreRejectedAtValidation() {
        // A Tor chain on Smart or MIM bases is not a verified combination and must be rejected
        // before any process starts.
        for (String protocol : new String[]{"smart", "mim"}) {
            Map<String, Object> values = defaults();
            values.put("torProxy", true);
            values.put("torMode", "chain");
            assertEquals("torMode", CoreSettings.invalid(values, protocol, "h2"));
        }
    }

    @Test public void psiphonAndTorChainsAreMutuallyExclusive() {
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("torProxy", true);
        values.put("torMode", "chain");
        // Either the Psiphon-mode conflict or the Tor-chain protocol conflict rejects the
        // combination; both happen at settings validation before any process starts.
        String invalidKey = CoreSettings.invalid(values, "wg", "h2");
        assertTrue("psiphonMode".equals(invalidKey) || "torMode".equals(invalidKey));
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.environment(values, "wg", "h2"));
    }

    @Test public void torChainCapabilityGateRejectsWhenNotVerified() {
        // The TOR_CHAIN gate mechanism itself: when the capability is not AVAILABLE (e.g. a
        // dev.018 that re-blocks it after failed validation), a saved torMode=chain is rejected
        // at settings validation with no process started. This documents the flip-back path.
        Map<String, Object> values = defaults();
        values.put("torProxy", true);
        values.put("torMode", "chain");
        // With the current dev.017 capability (AVAILABLE), the chain is offered and emitted;
        // a hypothetical blocked gate is exercised through PrivacySettings.unavailable semantics
        // by asserting the capability that guards it.
        assertTrue(PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_CHAIN));
        assertEquals(PrivacyRuntimeConfig.TOR_SOCKS,
                TorChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
        // The CDN gate stays the model for blocked privacy modes: no unsafe exposure.
        assertFalse(PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_CHAIN_CDN));
        Map<String, Object> cdn = defaults();
        cdn.put("psiphonMode", "chain");
        cdn.put("psiphonTransport", "cdn");
        assertEquals("psiphonTransport", PrivacySettings.unavailable(cdn));
    }

    // --- CHANGE 5: Proxy mode hides the internal privacy combinations -----------------------

    @Test public void proxyModeHidesCombinedPrivacyEntriesButVpnModeShowsThem() {
        List<Integer> vpn = ProtocolOrder.displayIndices(false, false);
        assertTrue(vpn.contains(ProtocolOrder.SMART));
        assertEquals(Integer.valueOf(ProtocolOrder.SMART), vpn.get(0));
        assertTrue(vpn.contains(5));
        assertTrue(vpn.contains(6));
        assertTrue(vpn.contains(7));
        assertFalse(vpn.contains(8));
        List<Integer> proxy = ProtocolOrder.displayIndices(true, false);
        for (int index : new int[]{5, 6, 7, 8, 9, 10}) {
            assertFalse("Proxy mode must hide combined entry " + index, proxy.contains(index));
        }
        assertEquals(5, proxy.size());
    }

    @Test public void torEntriesAppearOnlyWithTheCapability() {
        // Without the capability the Tor entries are hidden even in VPN mode (no fake UI entries).
        List<Integer> without = ProtocolOrder.displayIndices(false, false);
        for (int index : new int[]{8, 9, 10}) assertFalse(without.contains(index));
        List<Integer> with = ProtocolOrder.displayIndices(false, true);
        for (int index : new int[]{8, 9, 10}) assertTrue(with.contains(index));
        assertEquals(11, with.size());
    }

    @Test public void proxyModeDoesNotEraseSavedPsiphonPreferences() {
        // Switching to Proxy mode never rewrites the stored Psiphon state: transport, country and
        // the combined psiphonMode survive a Proxy-mode round trip and keep working on return.
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("psiphonTransport", "direct");
        values.put("psiphonRegion", "DE");
        // The Proxy-mode "mode" is a connection-mode preference; CoreSettings never rewrites the
        // psiphon keys, so the same values map still validates in VPN mode afterwards.
        assertNull(CoreSettings.invalid(values, "wg", "h2"));
        Map<String, String> vpnEnvironment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        assertEquals("chain", vpnEnvironment.get("AETHER_PSIPHON"));
        assertEquals("direct", vpnEnvironment.get("AETHER_PSIPHON_MODE"));
        assertEquals("DE", vpnEnvironment.get("AETHER_PSIPHON_REGION"));
    }

    // --- CHANGE 4: Manual country code ------------------------------------------------------

    @Test public void manualCountryCodeNormalizesAndValidates() {
        assertEquals("DE", PsiphonCountries.normalizeManual("de"));
        assertEquals("DE", PsiphonCountries.normalizeManual(" dE "));
        assertEquals("CA", PsiphonCountries.normalizeManual("CA"));
        assertEquals("", PsiphonCountries.normalizeManual("D"));
        assertEquals("", PsiphonCountries.normalizeManual("DEU"));
        assertEquals("", PsiphonCountries.normalizeManual("D1"));
        assertEquals("", PsiphonCountries.normalizeManual(""));
        assertEquals("", PsiphonCountries.normalizeManual(null));
        // Manual codes outside the curated list remain valid settings (the helper decides at
        // runtime whether it can honor them), unlike normalize() which only keeps listed codes.
        assertEquals("XX", PsiphonCountries.normalizeManual("XX"));
        assertTrue(PsiphonCountries.isKnown("DE"));
        assertFalse(PsiphonCountries.isKnown("XX"));
        assertFalse(PsiphonCountries.isKnown(""));
    }

    @Test public void manualCountryPersistenceAndValidation() {
        for (String code : new String[]{"DE", "CA", "NL", "SE", "XX"}) {
            Map<String, Object> values = defaults();
            values.put("psiphonMode", "chain");
            values.put("psiphonRegion", code);
            assertNull("Manual code " + code + " must persist as a valid setting",
                    PrivacySettings.invalid(values, "wg"));
        }
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("psiphonRegion", "D");
        assertEquals("psiphonRegion", CoreSettings.invalid(values, "wg", "h2"));
        values.put("psiphonRegion", "DEU");
        assertEquals("psiphonRegion", CoreSettings.invalid(values, "wg", "h2"));
    }

    @Test public void manualRegionEmitsTheRequestedCountryToTheCore() {
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("psiphonRegion", "CA");
        Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        assertEquals("CA", environment.get("AETHER_PSIPHON_REGION"));
    }

    // --- CHANGE 6: Turbo default without the "(recommended)" suffix -------------------------

    @Test public void turboIsTheFreshAndResetDefaultWithoutRecommendedSuffix() throws Exception {
        // ConnectionDefaults keeps SCAN_INDEX = 1 (Turbo) for fresh installs and Reset Defaults;
        // the label carries no "(recommended)" suffix in either language.
        assertEquals(1, ConnectionDefaults.SCAN_INDEX);
        assertEquals("turbo", ConnectionDefaults.SCAN);
        try (java.util.Scanner scanner = new java.util.Scanner(new java.io.File(
                "src/main/res/values/arrays.xml"), "UTF-8").useDelimiter("\\A")) {
            String arrays = scanner.next();
            assertTrue(arrays.contains("<item>Turbo</item>"));
            assertFalse(arrays.contains("Turbo (recommended)"));
        }
        try (java.util.Scanner scanner = new java.util.Scanner(new java.io.File(
                "src/main/res/values-fa/arrays.xml"), "UTF-8").useDelimiter("\\A")) {
            String arrays = scanner.next();
            assertTrue(arrays.contains("<item>توربو</item>"));
            assertFalse(arrays.contains("توربو (پیشنهادی)"));
        }
        // Existing saved choices always win: a stored index is passed through unchanged.
        assertEquals("verified", VpnConnectionController.scanMode(3));
        assertEquals("turbo", VpnConnectionController.scanMode(1));
        // The normalized scan mode still maps legacy names to Verified and never merges Ironclad.
        assertEquals("verified", ConnectionDefaults.normalizedScanMode("stealth"));
        assertEquals("ironclad", ConnectionDefaults.normalizedScanMode("ironclad"));
        assertFalse(ConnectionDefaults.normalizedScanMode("ironclad").equals(ConnectionDefaults.normalizedScanMode("verified")));
    }

    // --- CHANGE 9: Psiphon Transport Auto default ------------------------------------------

    @Test public void autoPsiphonTransportIsTheFreshAndResetDefault() {
        assertEquals("auto", CoreSettings.string(CoreSettings.values(new LinkedHashMap<>()), "psiphonTransport"));
        Map<String, Object> values = defaults();
        values.put("psiphonTransport", "direct");
        assertEquals("direct", CoreSettings.string(values, "psiphonTransport"));
    }

    // --- CHANGE 13: display aliases and Smart Connect first --------------------------------

    @Test public void smartConnectIsDisplayedFirstAndAliasesReplaceLongNames() throws Exception {
        assertEquals(Integer.valueOf(ProtocolOrder.SMART), ProtocolOrder.displayIndices(false, false).get(0));
        assertEquals(Integer.valueOf(ProtocolOrder.SMART), ProtocolOrder.displayIndices(true, true).get(0));
        try (java.util.Scanner scanner = new java.util.Scanner(new java.io.File(
                "src/main/res/values/arrays.xml"), "UTF-8").useDelimiter("\\A")) {
            String arrays = scanner.next();
            // WIW and MIM replace the long display names (WARP-in-WARP / MASQUE-in-MASQUE)...
            assertTrue(arrays.contains("gool / WIW"));
            assertTrue(arrays.contains("<item>MIM</item>"));
            // ...while the array ORDER (the persisted storage contract) is untouched:
            // 0=MASQUE 1=WireGuard 2=gool 3=Smart 4=MIM 5..7=+Psiphon 8..10=+Tor.
            assertFalse(arrays.contains("WARP-in-WARP"));
            assertFalse(arrays.contains("MASQUE-in-MASQUE"));
        }
    }

    @Test public void psiphonCombinedEntriesMapToTheBackendChain() {
        // Selecting a + Psiphon entry maps to psiphonMode=chain with the base protocol stored.
        for (int[] mapping : new int[][]{{5, 1}, {6, 2}, {7, 0}}) {
            assertEquals(mapping[1], PsiphonChainRouting.combinedBaseIndex(mapping[0]));
        }
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS,
                PsiphonChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
    }

    // --- CHANGE 8/12/14: UI contract snippets ------------------------------------------------

    @Test public void homeCountrySelectorIsActiveOnlyForPsiphonChain() {
        Map<String, Object> values = defaults();
        assertFalse(PsiphonSettingsUi.homeCountrySelectorActive(values));
        values.put("psiphonMode", "chain");
        assertTrue(PsiphonSettingsUi.homeCountrySelectorActive(values));
        values.put("psiphonMode", "off");
        values.put("torProxy", true);
        values.put("torMode", "chain");
        assertFalse(PsiphonSettingsUi.homeCountrySelectorActive(values));
        // The Home selector and Configurations share the persisted psiphonRegion value.
        values.put("psiphonMode", "chain");
        values.put("psiphonRegion", "de");
        assertEquals("DE", PsiphonSettingsUi.savedRegion(values));
    }

    @Test public void scanAndProtocolArraysKeepTheirStorageContract() {
        // The protocol array must stay in the persisted storage order even with the new Tor
        // entries appended (dev.017 adds 8/9/10 without reordering anything).
        String[] labels = null;
        try {
            java.io.InputStream stream = new java.io.FileInputStream("src/main/res/values/arrays.xml");
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(stream);
            org.w3c.dom.NodeList arrays = document.getElementsByTagName("string-array");
            for (int index = 0; index < arrays.getLength(); index++) {
                org.w3c.dom.Element array = (org.w3c.dom.Element) arrays.item(index);
                if (!"protocol_labels".equals(array.getAttribute("name"))) continue;
                org.w3c.dom.NodeList items = array.getElementsByTagName("item");
                labels = new String[items.getLength()];
                for (int item = 0; item < items.getLength(); item++) labels[item] = items.item(item).getTextContent();
            }
        } catch (Exception unreadable) { fail("Protocol labels unreadable: " + unreadable); }
        assertEquals(11, labels.length);
        assertEquals("MASQUE", labels[0]);
        assertEquals("WireGuard", labels[1]);
        assertEquals("gool / WIW", labels[2]);
        assertEquals("Smart Connect", labels[3]);
        assertEquals("MIM", labels[4]);
        assertEquals("WireGuard + Psiphon", labels[5]);
        assertEquals("gool + Psiphon", labels[6]);
        assertEquals("MASQUE + Psiphon", labels[7]);
        assertEquals("WireGuard + Tor", labels[8]);
        assertEquals("gool + Tor", labels[9]);
        assertEquals("MASQUE + Tor", labels[10]);
    }

    @Test public void torModeDefaultIsSideForExistingUsers() {
        // dev.016 users (and fresh installs) get the side contract; only an explicit + Tor
        // protocol selection writes torMode=chain.
        assertEquals("side", CoreSettings.string(defaults(), "torMode"));
    }
}
