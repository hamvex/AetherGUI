package com.firstham.aethergui;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.020 CHANGE 6 regression coverage: the Tor T1/Home-LOCATION defect. Cloudflare's trace
 * answers {@code loc=T1} for Tor exits; the old length-2 acceptance let "T1" through as a
 * "country" and rendered a broken flag + "T1" on Home LOCATION. The validation chain must now
 * accept only strict ISO 3166-1 alpha-2 codes, reject T1/non-country markers everywhere (trace
 * normalization, the flag renderer, and the final rendered line), and degrade to the truthful
 * "Location unavailable" state instead of fabricating data.
 *
 * <p>Note: org.json is not mocked in JVM unit tests, so provider-JSON cases are covered through
 * the pure normalization/trace helpers that countryCodeFromJson() delegates to.
 */
public final class TorCountryValidationTest {

    // --- The exact defect -------------------------------------------------------------------

    @Test public void torMarkerT1IsNeverAnIsoCountryCode() {
        // "T1" has length 2, which the old check accepted. It must now be rejected: it is not
        // an ISO 3166-1 alpha-2 code and must never become a flag.
        assertEquals("", AetherVpnService.normalizedCountryCode("T1", ""));
        assertEquals("", AetherVpnService.normalizedCountryCode("t1", ""));
        assertTrue(AetherVpnService.isNonCountryMarker("T1"));
    }

    @Test public void malformedFlagLikeFragmentsAreRejected() {
        // A broken flag-like character followed by T1, digits, lowercase junk: all rejected by
        // the strict ISO shape. (User-assigned two-letter codes such as XX keep the valid SHAPE
        // - the marker set is what screens the Tor family at the trace level.)
        for (String bad : new String[]{"T1", "T2", "12", "A1", "1A", "t1", "IRN", ""}) {
            assertFalse("code '" + bad + "' must not validate as ISO alpha-2",
                    AetherVpnService.isIsoAlpha2(bad));
        }
        for (String marker : new String[]{"T1", "T2", "XX"}) {
            assertTrue(AetherVpnService.isNonCountryMarker(marker));
        }
    }

    @Test public void realCountriesStillValidate() {
        for (String good : new String[]{"DE", "NL", "GB", "IR", "US", "AT", "SE", "CA"}) {
            assertEquals(good, AetherVpnService.normalizedCountryCode(good, ""));
            assertTrue(AetherVpnService.isIsoAlpha2(good));
            assertFalse(AetherVpnService.isNonCountryMarker(good));
        }
        // The name fallback still resolves.
        assertEquals("IR", AetherVpnService.normalizedCountryCode("", "Iran"));
        assertEquals("IR", AetherVpnService.normalizedCountryCode("IRN", "Iran"));
    }

    @Test public void renderedLocationLinesAreOnlyTrustedForIsoCountries() {
        // The final rendered line validation: only flag + ISO code lines pass; the T1 line
        // (however it might be constructed) fails and degrades to unavailable.
        assertFalse(AetherVpnService.countryCodesValid("\uD83C\uDDED T1")); // half flag + T1
        assertFalse(AetherVpnService.countryCodesValid("T1"));
        assertFalse(AetherVpnService.countryCodesValid(""));
        assertFalse(AetherVpnService.countryCodesValid(null));
        assertFalse(AetherVpnService.countryCodesValid("xx DE"));
        assertTrue(AetherVpnService.countryCodesValid("\uD83C\uDDE9\uD83C\uDDEA DE"));
        assertTrue(AetherVpnService.countryCodesValid("\uD83C\uDDE9\uD83C\uDDEA Berlin"));
        // A properly formed flag followed by the Tor marker is still rejected: the token after
        // the flag is not an ISO code.
        assertFalse(AetherVpnService.countryCodesValid("\uD83C\uDDE9\uD83C\uDDEA T1"));
    }

    @Test public void torChainLocationRemainsReadOnlyInHomeUi() {
        // + Tor: the Home LOCATION row is never an editable selector (the Tor exit is chosen
        // by the Tor network); the Psiphon chain selector contract is unchanged.
        Map<String, Object> tor = new LinkedHashMap<>();
        tor.put("psiphonMode", "off");
        tor.put("torProxy", true);
        tor.put("torMode", "chain");
        assertFalse(PsiphonSettingsUi.homeCountrySelectorActive(tor));
        Map<String, Object> psiphon = new LinkedHashMap<>();
        psiphon.put("psiphonMode", "chain");
        assertTrue(PsiphonSettingsUi.homeCountrySelectorActive(psiphon));
    }

    // --- Tor exit resolution through the existing trustworthy pipeline -----------------------

    @Test public void traceTorMarkerTriggersProviderCountryResolution() {
        // The trace path: loc=T1 is detected as a non-country marker so the lookup does NOT
        // fabricate a flag from it; the provider resolution path (the same trustworthy
        // geo-IP providers the lookup already uses for this exit IP) becomes the source of the
        // real Tor exit country. countryCodeFromJson delegates to the same normalization this
        // test exercises: a provider answering country_code=T1 yields no country, a real
        // two-letter country_code yields the country.
        String rawTraceCountry = AetherVpnService.traceValue("fl=abc\nip=204.8.96.174\nloc=T1\ntls=TLSv1.3\n", "loc");
        assertEquals("T1", rawTraceCountry);
        assertTrue(AetherVpnService.isNonCountryMarker(rawTraceCountry));
        // The marker never survives normalization - it cannot become a rendered country.
        assertEquals("", AetherVpnService.normalizedCountryCode(rawTraceCountry, ""));
        // A real provider-style answer yields the country.
        assertEquals("US", AetherVpnService.normalizedCountryCode("US", "United States"));
        // The trace's exit IP parses exactly, so the provider query for that IP is well-formed.
        assertEquals("204.8.96.174", AetherVpnService.traceValue("ip=204.8.96.174\nloc=T1", "ip"));
    }

    @Test public void cacheCannotReintroduceTorMarkerValues() {
        // A stale cached value containing a marker line is discarded by the display validation
        // (scheduleLocationLookup's countryCodesValid gate), so the cache can never put "T1"
        // back onto the Home LOCATION row.
        assertFalse(AetherVpnService.countryCodesValid("\uD83C\uDDED T1"));
        assertFalse(AetherVpnService.countryCodesValid("\uD83C\uDDE9\uD83C\uDDEA T1"));
        // and a valid cached line survives.
        assertTrue(AetherVpnService.countryCodesValid("\uD83C\uDDF3\uD83C\uDDF1 NL"));
    }
}
