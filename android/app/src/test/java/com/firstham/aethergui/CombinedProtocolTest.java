package com.firstham.aethergui;

import org.junit.Test;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

/**
 * Regression coverage for the dev.016 Protocol + Psiphon combined entries, the country selector
 * and the More Settings navigation contract.
 */
public class CombinedProtocolTest {
    @Test public void combinedEntriesExistForVerifiedBaseProtocolsOnly() {
        // Indices 5/6/7 map to WireGuard, gool and MASQUE; the blocked topologies and unverified
        // combinations (Smart, MIM, reverse) get no combined entry.
        assertEquals(1, PsiphonChainRouting.combinedBaseIndex(5));
        assertEquals(2, PsiphonChainRouting.combinedBaseIndex(6));
        assertEquals(0, PsiphonChainRouting.combinedBaseIndex(7));
        assertEquals(-1, PsiphonChainRouting.combinedBaseIndex(0));
        assertEquals(-1, PsiphonChainRouting.combinedBaseIndex(3));
        assertEquals(-1, PsiphonChainRouting.combinedBaseIndex(4));
        assertEquals(-1, PsiphonChainRouting.combinedBaseIndex(8));
        assertEquals(5, PsiphonChainRouting.combinedProtocolIndex(1));
        assertEquals(6, PsiphonChainRouting.combinedProtocolIndex(2));
        assertEquals(7, PsiphonChainRouting.combinedProtocolIndex(0));
    }

    @Test public void chainRoutingFollowsTheSavedPsiphonModeNotTheProtocolIndex() {
        // The persisted "protocol" always stores the base protocol; the chain data plane keys off
        // psiphonMode=chain, so a stored combined selection from a previous session, a backup or
        // an older build maps through the same runtime path.
        for (int base : new int[]{0, 1, 2}) {
            java.util.Map<String, Object> values = CoreSettings.values(new java.util.LinkedHashMap<>());
            values.put("protocol", base);
            assertFalse(PsiphonChainRouting.chainActive(values));
            values.put("psiphonMode", "chain");
            assertTrue(PsiphonChainRouting.chainActive(values));
            assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS,
                    PsiphonChainRouting.effectiveSocks(values, "vpn", "127.0.0.1:1819"));
        }
    }

    @Test public void countrySelectorOffersOnlyVerifiedRegionsWithAutomatic() {
        List<String> codes = PsiphonCountries.codes();
        assertEquals(26, codes.size());
        assertTrue(codes.contains("DE"));
        assertTrue(codes.contains("CA"));
        assertTrue(codes.contains("US"));
        assertFalse(codes.contains("IR"));
        // Persistence keeps the two-letter ISO code; anything else normalizes to Automatic.
        assertEquals("DE", PsiphonCountries.normalize("de"));
        assertEquals("DE", PsiphonCountries.normalize("DE"));
        assertEquals("", PsiphonCountries.normalize("XX"));
        assertEquals("", PsiphonCountries.normalize(""));
        assertEquals("", PsiphonCountries.normalize(null));
        // Labels carry a flag and a country name; the Automatic line is localized by the caller.
        String label = PsiphonCountries.label("DE", Locale.ENGLISH);
        assertTrue(label.startsWith(PsiphonCountries.flag("DE")));
        assertTrue(label.toLowerCase(Locale.ROOT).contains("germany"));
        assertEquals("", PsiphonCountries.flag("de"));
        assertEquals("", PsiphonCountries.flag("D"));
        assertEquals("", PsiphonCountries.flag(""));
    }

    @Test public void savedRegionValuesRemainValidAcrossTheSelector() {
        // The saved string keeps its exact dev.015 semantics: two letters, uppercase, empty means
        // automatic. The selector only changes how users choose it.
        for (String code : PsiphonCountries.codes()) {
            java.util.Map<String, Object> values = CoreSettings.values(new java.util.LinkedHashMap<>());
            values.put("psiphonMode", "chain");
            values.put("psiphonRegion", code);
            assertNull(PrivacySettings.invalid(values, "wg"));
        }
    }
}
