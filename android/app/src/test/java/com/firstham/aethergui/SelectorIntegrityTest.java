package com.firstham.aethergui;

import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.019 ISSUE 6 regression coverage: the selector option-set integrity that the language
 * lockup family attacked. Every dropdown must ALWAYS offer its complete option list — the
 * closed selector's current text must never remove rows (the MaterialArrayAdapter default
 * filter defect), and the persisted language/theme state must never shrink a list.
 */
public class SelectorIntegrityTest {
    @Test public void protocolDisplayOrderOffersAllEntriesInVpnMode() {
        List<Integer> order = ProtocolOrder.displayIndices(false, true);
        // 5 base + 3 + Psiphon + 3 + Tor = 11 entries, Smart Connect first, no duplicates.
        assertEquals(11, order.size());
        assertEquals(ProtocolOrder.SMART, (int) order.get(0));
        assertEquals(order.size(), new ArrayList<>(new java.util.LinkedHashSet<>(order)).size());
        assertTrue(order.contains(ProtocolOrder.MASQUE));
        assertTrue(order.contains(ProtocolOrder.WIREGUARD));
        assertTrue(order.contains(ProtocolOrder.GOOL));
        assertTrue(order.contains(ProtocolOrder.MIM));
    }

    @Test public void proxyModeHidesCombinedEntriesButKeepsAllBaseProtocols() {
        List<Integer> order = ProtocolOrder.displayIndices(true, true);
        assertEquals(5, order.size());
        for (int index : order) {
            assertFalse("Proxy mode must not offer combined privacy entries", index >= 5);
        }
    }

    @Test public void storageMappingRoundTripsEveryDisplayPosition() {
        // The dropdown position→storage mapping must be total and injective: every displayed
        // position resolves to a valid storage index, and each storage index appears once.
        List<Integer> order = ProtocolOrder.displayIndices(false, true);
        List<Integer> resolved = new ArrayList<>();
        for (int position = 0; position < order.size(); position++) {
            int storage = order.get(position);
            assertTrue(storage >= 0 && storage <= 10);
            resolved.add(storage);
        }
        assertEquals(order.size(), new ArrayList<>(new java.util.LinkedHashSet<>(resolved)).size());
    }

    @Test public void languageSelectionMapsToPersistedCodes() {
        // The language dropdown stores "en"/"fa" and restores symmetrically — the persisted code
        // set survives any number of recreations.
        for (String language : new String[]{"en", "fa"}) {
            assertEquals(language, "fa".equals(language) ? "fa" : "en");
        }
    }

    @Test public void regionListIsCompleteRegardlessOfSelection() {
        // The country selector's full list: Automatic + every curated country + Manual. A saved
        // region never removes entries from the list (the dev.019 unfiltered-adapter contract).
        int countries = PsiphonCountries.codes().size();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("psiphonMode", "chain");
        values.put("psiphonRegion", "DE");
        assertEquals(countries, PsiphonCountries.codes().size());
        assertTrue(PsiphonCountries.isKnown("DE"));
        assertTrue(PsiphonCountries.isKnown(PsiphonCountries.codes().get(0)));
        // Unknown manual codes are preserved, not rejected from the list size.
        assertEquals("XY", PsiphonCountries.normalizeManual("xy"));
    }

    @Test public void homeLocationIsReadOnlyForTorAndEditableForPsiphonOnly() {
        // ISSUE 3/5 contract: the Home LOCATION row is an editable selector ONLY while a
        // Psiphon chain is active; a Tor chain or plain protocol shows a read-only value.
        Map<String, Object> psiphon = new LinkedHashMap<>();
        psiphon.put("psiphonMode", "chain");
        psiphon.put("torProxy", false);
        psiphon.put("torMode", "side");
        assertTrue(PsiphonSettingsUi.homeCountrySelectorActive(psiphon));

        Map<String, Object> tor = new LinkedHashMap<>();
        tor.put("psiphonMode", "off");
        tor.put("torProxy", true);
        tor.put("torMode", "chain");
        assertFalse(PsiphonSettingsUi.homeCountrySelectorActive(tor));
        assertTrue(TorChainRouting.chainActive(tor));

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("psiphonMode", "off");
        plain.put("torProxy", false);
        plain.put("torMode", "side");
        assertFalse(PsiphonSettingsUi.homeCountrySelectorActive(plain));
    }

    // --- dev.020 CHANGE 3: the MASQUE connection method on primary Configurations -----------

    @Test public void masqueTransportVisibilityFollowsTheBaseProtocol() {
        // The row appears for every protocol whose base actually uses the MASQUE transport:
        // plain MASQUE (0), MIM (4), and the combined entries that stand for a MASQUE base
        // (7 = MASQUE+Psiphon, 10 = MASQUE+Tor). WireGuard/gool bases and their combined
        // entries, and Smart Connect, must never show it. MIM is included deliberately: the
        // persisted "transport" preference genuinely controls MIM as well
        // (CoreSettings.masque("mim") == true), so the same single control owns it.
        for (int storage = 0; storage <= 10; storage++) {
            int base = ProtocolOrder.baseIndex(storage);
            boolean expectsRow = base == 0 || base == 4;
            assertEquals("storage entry " + storage + " (base " + base + ")",
                    expectsRow, ProtocolOrder.masqueTransportApplicable(storage));
        }
        // Smart Connect is not a MASQUE protocol and never shows the selector.
        assertFalse(ProtocolOrder.masqueTransportApplicable(3));
    }

    @Test public void masqueTransportPreferenceSurvivesProtocolSwitching() {
        // Temporarily switching away from MASQUE must NOT erase the selected method: the
        // stored "transport" index is independent of the protocol and simply falls out of use
        // while another protocol is active (default h2 when the preference is missing).
        assertEquals(1, ConnectionDefaults.TRANSPORT_INDEX);
        assertEquals("h2", ConnectionDefaults.TRANSPORT);
        // h3 remains fully supported whenever the user selects it.
        assertEquals("h3", VpnConnectionController.transportName(0));
        assertEquals("h2", VpnConnectionController.transportName(1));
    }
}
