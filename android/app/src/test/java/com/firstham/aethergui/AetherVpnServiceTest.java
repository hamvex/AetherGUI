package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AetherVpnServiceTest {
    @Test public void capsMtuForTransportEncapsulation() {
        assertEquals(1400, mtu("masque", 1500));
        assertEquals(1420, mtu("wg", 1500));
        assertEquals(1360, mtu("gool", 1500));
        assertEquals(1280, mtu("mim", 1500));
    }

    @Test public void preservesSafeUserMtuAndIpv6Minimum() {
        assertEquals(1320, mtu("masque", 1320));
        assertEquals(1280, mtu("wg", 1100));
        assertEquals(1280, mtu("gool", 1280));
    }

    @Test public void nestedWireguardNeverRunsAtFullEthernetMtu() {
        assertTrue(mtu("gool", 1500) < 1500);
        assertTrue(mtu("gool", 9000) <= 1360);
        assertEquals(1360, mtu("gool", 1361));
        assertEquals(1359, mtu("gool", 1359));
    }

    @Test public void unknownProtocolFallsBackToTheMasqueCeiling() {
        assertEquals(1400, mtu("", 1500));
        assertEquals(1400, mtu("future-transport", 1500));
    }

    @Test public void manualMtuDefaultsAndClampsToSupportedRange() {
        assertEquals(1360, VpnConnectionController.parseMtu(""));
        assertEquals(1360, VpnConnectionController.parseMtu("not-a-number"));
        assertEquals(1280, VpnConnectionController.parseMtu("1200"));
        assertEquals(1360, VpnConnectionController.parseMtu("1360"));
        assertEquals(1500, VpnConnectionController.parseMtu("9000"));
    }

    @Test public void automaticMtuModeMustBeExplicit() {
        assertEquals("automatic", VpnConnectionController.normalizedMtuMode("automatic"));
        assertEquals("automatic", VpnConnectionController.normalizedMtuMode("AUTOMATIC"));
        assertEquals("manual", VpnConnectionController.normalizedMtuMode("manual"));
        assertEquals("manual", VpnConnectionController.normalizedMtuMode("legacy"));
        assertEquals("manual", VpnConnectionController.normalizedMtuMode(null));
    }

    @Test public void ipv6IsOnlyForwardedForIpv6CapableCoreModes() {
        assertFalse(AetherVpnService.ipv6ModeRequested("v4"));
        assertFalse(AetherVpnService.ipv6ModeRequested(""));
        assertTrue(AetherVpnService.ipv6ModeRequested("v6"));
        assertTrue(AetherVpnService.ipv6ModeRequested("both"));
    }

    @Test public void transitionalStatesAreRecognisedAsConnecting() {
        for (String state : new String[]{"starting", "smart-testing", "scanning", "securing", "reconnecting"}) {
            assertTrue(state, AetherVpnService.isConnectingState(state));
        }
        for (String state : new String[]{"connected", "disconnected", "disconnecting", "error", "blocked", ""}) {
            assertFalse(state, AetherVpnService.isConnectingState(state));
        }
    }

    @Test public void healthProbeAlsoRunsWhileRecovering() {
        // A recovered tunnel used to stay published as "reconnecting" because the prober that could
        // have proven it healthy was gated on "connected" alone.
        assertTrue(AetherVpnService.healthProbeApplies("connected"));
        assertTrue(AetherVpnService.healthProbeApplies("reconnecting"));
        for (String state : new String[]{"starting", "smart-testing", "scanning", "securing",
                "disconnecting", "disconnected", "error", "blocked", ""}) {
            assertFalse(state, AetherVpnService.healthProbeApplies(state));
        }
    }

    @Test public void iranLocationRemainsAvailableForDisplay() {
        assertEquals("IR", AetherVpnService.normalizedCountryCode("ir", ""));
        assertEquals("IR", AetherVpnService.normalizedCountryCode("", "Iran"));
        assertEquals("SE", AetherVpnService.normalizedCountryCode("se", "Sweden"));
        assertEquals("", AetherVpnService.normalizedCountryCode("", "Sweden"));
    }

    @Test public void cloudflareTraceValuesAreParsedWithoutCrossLineMatches() {
        String trace = "fl=abc\nip=203.0.113.8\nloc=SE\nwarp=off\n";
        assertEquals("203.0.113.8", AetherVpnService.traceValue(trace, "ip"));
        assertEquals("SE", AetherVpnService.traceValue(trace, "loc"));
        assertEquals("", AetherVpnService.traceValue(trace, "country"));
    }

    @Test public void geoProviderRateLimitsUseBackoff() {
        assertTrue(AetherVpnService.providerShouldBackOff("Location service returned HTTP 403"));
        assertTrue(AetherVpnService.providerShouldBackOff("Location service returned HTTP 429"));
        assertFalse(AetherVpnService.providerShouldBackOff("Location service returned HTTP 500"));
    }

    @Test public void providerAddressMismatchIsRejected() {
        assertTrue(AetherVpnService.addressesMatch("203.0.113.8", "203.0.113.8"));
        assertTrue(AetherVpnService.addressesMatch("203.0.113.8", ""));
        assertFalse(AetherVpnService.addressesMatch("203.0.113.8", "198.51.100.2"));
    }

    @Test public void timingStampDoesNotDefeatDuplicateSmartConnectSuppression() {
        assertTrue(AetherVpnService.configurationKeyIgnored("requestedAtElapsed"));
        assertFalse(AetherVpnService.configurationKeyIgnored("protocol"));
    }

    @Test public void connectWatchdogOnlyGuardsTheInitialConnect() {
        // "reconnecting" is a mid-session recovery with its own bounded attempt counter, so the
        // initial-connect watchdog must not abort it.
        for (String state : new String[]{"starting", "smart-testing", "scanning", "securing"}) {
            assertTrue(state, AetherVpnService.initialConnectState(state));
        }
        for (String state : new String[]{"reconnecting", "connected", "disconnecting", "disconnected",
                "error", "blocked", ""}) {
            assertFalse(state, AetherVpnService.initialConnectState(state));
        }
    }

    @Test public void onlyRecoverableConnectFailuresAreRetried() {
        assertTrue(AetherVpnService.retryableConnectFailure("IllegalStateException", "SOCKS5 listener never became ready"));
        assertTrue(AetherVpnService.retryableConnectFailure("IOException", "Connection reset"));
        assertTrue(AetherVpnService.retryableConnectFailure("IllegalStateException", null));
        // A user-cancelled connect, a rejected configuration, an occupied port and an already
        // published session are all deterministic: retrying them just wastes the user's time.
        assertFalse(AetherVpnService.retryableConnectFailure("InterruptedException", "interrupted"));
        assertFalse(AetherVpnService.retryableConnectFailure("IllegalArgumentException", "bad socks address"));
        assertFalse(AetherVpnService.retryableConnectFailure("ProxyPortException", "HTTP port is occupied"));
        assertTrue(AetherVpnService.retryableConnectFailure("ProxyStartupException", "Listeners are not ready"));
        assertFalse(AetherVpnService.retryableConnectFailure("SupervisedSessionException", "session ended"));
        assertFalse(AetherVpnService.retryableConnectFailure("IllegalStateException", "Aether core is missing for this device architecture"));
    }

    @Test public void automaticMtuCeilingRespectsBothProtocolAndLinkMtu() {
        // No link MTU exposed (API < 29 or unknown): the protocol ceiling is all we have.
        assertEquals(1400, AetherVpnService.automaticMtuCeiling("masque", 0));
        assertEquals(1420, AetherVpnService.automaticMtuCeiling("wg", -1));
        assertEquals(1360, AetherVpnService.automaticMtuCeiling("gool", 0));
        // A 1500-byte link cannot beat the protocol ceiling.
        assertEquals(1400, AetherVpnService.automaticMtuCeiling("masque", 1500));
        // A smaller link MTU must subtract the encapsulation overhead, not be used verbatim.
        assertEquals(1300, AetherVpnService.automaticMtuCeiling("masque", 1400));
        assertEquals(1320, AetherVpnService.automaticMtuCeiling("wg", 1400));
        assertEquals(1280, AetherVpnService.automaticMtuCeiling("gool", 1400));
        // The ceiling never drops below the IPv6 minimum, however small the link claims to be.
        assertEquals(1280, AetherVpnService.automaticMtuCeiling("masque", 1280));
        assertEquals(1280, AetherVpnService.automaticMtuCeiling("gool", 1300));
    }

    @Test public void automaticMtuCandidatesAreProbedLargestFirst() {
        int[] candidates = AetherVpnService.mtuCandidates();
        assertEquals(6, candidates.length);
        assertEquals(1500, candidates[0]);
        assertEquals(1280, candidates[candidates.length - 1]);
        for (int index = 1; index < candidates.length; index++) {
            assertTrue("descending", candidates[index] < candidates[index - 1]);
        }
    }

    @Test public void smartMtuSearchesOnlyCandidatesThatCanFit() {
        // Ascending, because the search bisects the list; and never containing a value the ceiling
        // has already ruled out, because probing one would waste a whole round trip proving nothing.
        int[] all = AetherVpnService.usableMtuCandidates(1500);
        assertEquals(6, all.length);
        assertEquals(1280, all[0]);
        assertEquals(1500, all[all.length - 1]);
        for (int index = 1; index < all.length; index++) {
            assertTrue("ascending", all[index] > all[index - 1]);
        }
        // gool's ceiling is 1360, so the three larger candidates must never be probed at all.
        int[] gool = AetherVpnService.usableMtuCandidates(AetherVpnService.automaticMtuCeiling("gool", 0));
        assertEquals(3, gool.length);
        assertEquals(1280, gool[0]);
        assertEquals(1360, gool[gool.length - 1]);
        // MASQUE's 1400 ceiling admits one more.
        assertEquals(4, AetherVpnService.usableMtuCandidates(AetherVpnService.automaticMtuCeiling("masque", 0)).length);
        // The list can never be empty: the floor equals the smallest candidate, so there is always
        // something to probe and the search never degenerates into an immediate fallback.
        int[] floor = AetherVpnService.usableMtuCandidates(AetherVpnService.automaticMtuCeiling("gool", 1300));
        assertEquals(1, floor.length);
        assertEquals(1280, floor[0]);
    }

    @Test public void smartMtuSearchIsBoundedAndFindsTheLargestPassingValue() {
        // Simulates the production loop over the ascending candidate list: probe the top first for
        // early success, then bisect. Asserts both that the answer is the largest passing value and
        // that the probe count stays well under the six a linear walk would need.
        int[] usable = AetherVpnService.usableMtuCandidates(1500);
        for (int cut = 0; cut < usable.length; cut++) {
            final int largestPassing = usable[cut];
            int lo = 0, hi = usable.length - 1, best = -1, probes = 0;
            boolean top = true;
            while (lo <= hi) {
                int index = top ? hi : lo + (hi - lo) / 2;
                top = false;
                probes++;
                if (usable[index] <= largestPassing) { best = index; lo = index + 1; }
                else hi = index - 1;
            }
            assertEquals("largest passing value", largestPassing, usable[best]);
            assertTrue("probes=" + probes + " must beat a linear walk", probes <= 4);
        }
    }

    @Test public void tileIsActiveWheneverTheInterfaceStillCarriesRoutes() {
        // STATE_ACTIVE = 2, STATE_INACTIVE = 1. "blocked" keeps the TUN up with traffic fail-closed,
        // so the tile has to stay active and clickable or the user cannot release it from the shade.
        assertEquals(2, AethonTileService.tileState("connected"));
        assertEquals(2, AethonTileService.tileState("blocked"));
        for (String state : new String[]{"disconnected", "error", "starting", "smart-testing",
                "scanning", "securing", "reconnecting", "disconnecting", ""}) {
            assertEquals(state, 1, AethonTileService.tileState(state));
        }
    }

    @Test public void tileNeverTrustsAStaleConnectedStateWithoutAVpnTransport() {
        // Service process killed while connected: the persisted value still says "connected" but
        // Android reports no VPN transport, so the tile must not claim protection.
        assertEquals("disconnected", AethonTileService.reconciledState("connected", false));
        assertEquals("disconnected", AethonTileService.reconciledState("blocked", false));
        assertEquals("disconnected", AethonTileService.reconciledState("starting", false));
        assertEquals("disconnected", AethonTileService.reconciledState("reconnecting", false));
        assertEquals("disconnected", AethonTileService.reconciledState("disconnecting", false));
        assertEquals("disconnected", AethonTileService.reconciledState(null, false));
        // A real VPN transport corroborates the persisted state, so it is preserved verbatim.
        assertEquals("connected", AethonTileService.reconciledState("connected", true));
        assertEquals("blocked", AethonTileService.reconciledState("blocked", true));
        // "error" is a terminal state the user should still see, and it is already inactive.
        assertEquals("error", AethonTileService.reconciledState("error", false));
        assertEquals("disconnected", AethonTileService.reconciledState("disconnected", false));
    }

    @Test public void everyDisconnectableStateIsAlsoClickableOnTheTile() {
        // Phase 7 regression guard: the tile used to render "blocked" as STATE_UNAVAILABLE, which
        // Android renders unclickable, even though canDisconnect() says the user can stop it.
        for (String state : new String[]{"starting", "smart-testing", "scanning", "securing",
                "connected", "reconnecting", "disconnecting", "blocked"}) {
            assertTrue(state, VpnConnectionController.canDisconnect(state));
            assertTrue(state, AethonTileService.tileState(state) != 0);
        }
        assertFalse(VpnConnectionController.canDisconnect("error"));
        assertFalse(VpnConnectionController.canDisconnect("disconnected"));
    }

    @Test public void onlyTruncationTellsUsAnythingAboutMtu() {
        // A truncated response is the signature of a path dropping full-size segments, so stepping
        // down to the next candidate is worthwhile. Everything else is a transport failure that
        // would repeat identically for every smaller candidate - on the device all three candidates
        // failed with "Read timed out" on a healthy link and burned 4.5s of connect time.
        assertTrue(AetherVpnService.mtuProbeFailureIsAboutSize("MTU probe response was truncated at 0B"));
        assertFalse(AetherVpnService.mtuProbeFailureIsAboutSize("Read timed out"));
        assertFalse(AetherVpnService.mtuProbeFailureIsAboutSize("Connection reset"));
        assertFalse(AetherVpnService.mtuProbeFailureIsAboutSize("connection closed"));
        assertFalse(AetherVpnService.mtuProbeFailureIsAboutSize("MTU probe returned no HTTP response"));
        assertFalse(AetherVpnService.mtuProbeFailureIsAboutSize(null));
    }

    @Test public void theTileRedrawsForEveryStateTheServicePublishes() {
        // Phase 7 regression guard for the stale-tile defect. requestListeningState() cannot refresh a
        // tile that is already listening, so the tile now redraws from the ACTION_STATUS broadcast
        // instead. That broadcast carries every state below, and each one has to map to a defined tile
        // state - if any mapped to STATE_UNAVAILABLE (0) the shade would render it unclickable.
        for (String state : new String[]{"starting", "smart-testing", "scanning", "securing",
                "connected", "reconnecting", "disconnecting", "blocked", "disconnected", "error"}) {
            int tile = AethonTileService.tileState(state);
            assertTrue(state, tile == 1 || tile == 2);
        }
        // The two states Android itself corroborates as an active VPN are the only active ones.
        assertEquals(2, AethonTileService.tileState("connected"));
        assertEquals(2, AethonTileService.tileState("blocked"));
        assertEquals(1, AethonTileService.tileState("disconnecting"));
    }

    @Test public void everyTrafficGateTargetIsIndependentAndActuallyUsable() {
        // The gate races its targets so that one unreachable endpoint cannot fail a healthy tunnel.
        // That only holds if each target can actually succeed. www.msftconnecttest.com could not: it
        // serves a certificate that does not match its own name, so the verified handshake this gate
        // requires failed on every single connect ("No subjectAltNames on the certificate match" in
        // the device log, SEC_E_WRONG_PRINCIPAL from curl outside the tunnel). Racing a target that
        // can only lose reduced three-way redundancy to two.
        String[] hosts = AetherVpnService.trafficReadyHosts();
        assertEquals(3, hosts.length);
        for (String host : hosts) {
            assertFalse(host, host.contains("msftconnecttest"));
        }
        // Independent operators, so a single provider outage cannot fail the gate.
        java.util.Set<String> domains = new java.util.HashSet<>();
        for (String host : hosts) {
            String[] labels = host.split("\\.");
            domains.add(labels[labels.length - 2] + "." + labels[labels.length - 1]);
        }
        assertEquals(3, domains.size());
    }

    @Test public void theTrafficGateRerollsTheEndpointInsteadOfTearingTheVpnDown() {
        int[] bounds = AetherVpnService.trafficGateBounds();
        // Turbo hands over an endpoint pair the core already marked validated, yet roughly half of
        // those pairs carry no real HTTPS traffic, and the outcome is per-endpoint and bimodal: a
        // dead pair never recovers, so retrying the same pair harder cannot help. Only a fresh pair
        // can, and only a core restart produces one. With p(usable) near a half, the first gate plus
        // four re-rolls is five independent draws, which is what turns a coin flip into reliability.
        assertTrue("re-rolls=" + bounds[0], bounds[0] >= 4);
        // The first gate keeps two attempts so a genuine one-off blip still passes without a restart.
        assertEquals(2, bounds[1]);
        // A re-rolled pair is fresh, so one probe round decides it; a second would only burn budget.
        assertEquals(1, bounds[2]);
    }

    @Test public void theTrafficGateCannotOutliveTheConnectWatchdog() {
        long gate = AetherVpnService.worstCaseTrafficGateMs();
        long watchdog = AetherVpnService.connectWatchdogMs();
        long margin = AetherVpnService.trafficGateTimings()[0];
        // The live deadline is the earlier of the gate's own budget and the watchdog less this margin,
        // so the gate always reports its own reason instead of being cut off by a timeout that
        // explains nothing. The static ceiling has to respect the same bound.
        assertTrue("gate=" + gate + "ms watchdog=" + watchdog + "ms", gate + margin < watchdog);
        assertTrue("margin=" + margin + "ms", margin > 0);
    }

    @Test public void privacyChainProbesGetTheBudgetTheirFirstRequestNeeds() {
        int[] timeouts = AetherVpnService.trafficProbeTimeouts();
        int warpDirect = timeouts[0], chain = timeouts[1];
        // Tor's first request through a freshly bootstrapped circuit (selection, DNS over the
        // chain, TLS) plus every round trip traversing the chain carrier measurably exceeds
        // the 4s WARP-direct budget: on the dev.036 physical device every first
        // MASQUE-carried Tor probe timed out at 4s while Tor was fully ready, and
        // MASQUE-carried Psiphon chains only succeeded on attempt 2. The chain budget keeps
        // the first-attempt probes honest instead of churning teardown/re-roll cycles.
        assertTrue("warp direct=" + warpDirect + "ms", warpDirect > 0 && warpDirect <= 5_000);
        assertTrue("chain=" + chain + "ms", chain >= 12_000);
        // The static gate ceiling includes the chain probe, so the watchdog bound above still
        // holds with the worst case.
        long gate = AetherVpnService.worstCaseTrafficGateMs();
        assertTrue("gate=" + gate + "ms", gate >= 55_000L + chain);
    }

    @Test public void aReRollWaitsLongEnoughForTheCoreToActuallyStart() {
        long[] timings = AetherVpnService.trafficGateTimings();
        long socksWait = timings[1];
        long minRollBudget = timings[2];
        // A re-roll is a full core start and the core re-runs its whole gateway scan, which is exactly
        // why it yields a fresh endpoint pair. On SM-A556E a start that succeeds listens 2.4-8.8s in,
        // but one whose first sweep finds nothing re-scans on a ~25s cycle. At 12s a re-roll was
        // measured finding its endpoints and then being abandoned 9s before it could listen, which
        // wasted the roll entirely, so the wait has to span that re-scan cycle.
        assertTrue("roll socks wait=" + socksWait + "ms", socksWait >= 25_000L);
        // And a roll is only started with enough budget left to wait out a normal start and still run
        // the probe round that decides it; otherwise the roll is spent without ever being tested.
        assertTrue("min roll budget=" + minRollBudget + "ms", minRollBudget >= 14_000L);
    }

    @Test public void privacyChainsGetTheBootstrapAllowanceTheirCarrierNeeds() {
        long[] bounds = AetherVpnService.privacyAnnouncementBounds();
        long base = bounds[0], torAllowance = bounds[1], masqueAllowance = bounds[2];
        // Core v2.3.0 holds the Psiphon helper until the carrier tunnel validates and then
        // bootstraps its server list through that tunnel. The dev.034 physical device measured
        // 14.5s from carrier-open to "psiphon is ready" on a fast link and reproduced the flat
        // 30s window expiring under load, so a MASQUE-carried Psiphon chain needs the same
        // kind of allowance the Tor consensus gets.
        assertTrue("base=" + base + "ms", base > 0);
        assertTrue("masque allowance=" + masqueAllowance + "ms", masqueAllowance >= 120_000L);
        assertEquals(base + masqueAllowance,
                AetherVpnService.privacyAnnouncementTimeoutMs("masque", settings("masque", "chain", null)));
        assertEquals(base + masqueAllowance,
                AetherVpnService.privacyAnnouncementTimeoutMs("gool", settings("gool", "chain", "masque")));
        assertEquals(base + masqueAllowance,
                AetherVpnService.privacyAnnouncementTimeoutMs("mim", settings("mim", "chain", null)));
        // Tor chains keep their own (larger) consensus allowance, on every carrier.
        assertEquals(base + torAllowance,
                AetherVpnService.privacyAnnouncementTimeoutMs("wg", torSettings("wg")));
        assertEquals(base + torAllowance,
                AetherVpnService.privacyAnnouncementTimeoutMs("masque", torSettings("masque")));
        // A Psiphon chain on the fast WARP carriers (WireGuard, Classic Gool) keeps the base
        // window: the underlay opens in seconds and leaves the helper the full 30s.
        assertEquals(base,
                AetherVpnService.privacyAnnouncementTimeoutMs("wg", settings("wg", "chain", null)));
        assertEquals(base,
                AetherVpnService.privacyAnnouncementTimeoutMs("gool", settings("gool", "chain", "classic")));
        // No chain, no allowance.
        assertEquals(base,
                AetherVpnService.privacyAnnouncementTimeoutMs("masque", settings("masque", "off", null)));
    }

    @Test public void theChainAllowanceNeverDependsOnTheProtocolInsideTheSettingsMap() {
        // The real runtime maps (AndroidCoreSettings.fromIntent -> CoreSettings.values) carry
        // only CoreSettings.DEFAULTS keys, and "protocol" is deliberately not one of them; the
        // protocol travels as its own parameter. A dev.035 draft read it from the map, so the
        // MASQUE-chain allowance silently never applied and the gate stayed at the flat 30s.
        java.util.Map<String, Object> runtimeLike = settings("masque", "chain", null);
        runtimeLike.remove("protocol");
        assertEquals(AetherVpnService.privacyAnnouncementBounds()[0]
                        + AetherVpnService.privacyAnnouncementBounds()[2],
                AetherVpnService.privacyAnnouncementTimeoutMs("masque", runtimeLike));
    }

    private static java.util.Map<String, Object> settings(String protocol, String psiphonMode, String goolMode) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("protocol", protocol);
        values.put("psiphonMode", psiphonMode);
        if (goolMode != null) values.put("goolMode", goolMode);
        return values;
    }

    private static java.util.Map<String, Object> torSettings(String protocol) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("protocol", protocol);
        values.put("torMode", "chain");
        values.put("torProxy", Boolean.TRUE);
        return values;
    }

    @Test public void teardownIsBoundedSoDisconnectCannotHangForever() {
        long[] timings = AetherVpnService.teardownTimings();
        long bridgeStop = timings[0];
        long coreStop = timings[2];
        // TProxyStopService() joins the HEV tunnel worker, so from Java it is unbounded. Every wait on
        // the Disconnect path has to be finite or the single lifecycle thread stays occupied and every
        // later Connect queues behind it - the "several Disconnect taps" failure.
        assertTrue("bridge stop=" + bridgeStop + "ms", bridgeStop > 0 && bridgeStop <= 10_000L);
        assertTrue("core stop=" + coreStop + "ms", coreStop > 0);
        // A user watching the button needs the whole teardown to stay inside a few seconds even when
        // the bridge has to be abandoned.
        assertTrue("worst case teardown=" + (bridgeStop + coreStop) + "ms", bridgeStop + coreStop <= 8_000L);
    }

    @Test public void aNewBridgeWaitsOutAnAbandonedStopAtLeastAsLongAsTheStopItselfWaited() {
        long[] timings = AetherVpnService.teardownTimings();
        long bridgeStop = timings[0];
        long handover = timings[1];
        // Upstream TProxyStartService is a no-op while a work thread still exists, so a bridge started
        // over an unfinished stop carries no traffic while reporting success. The handover wait must
        // therefore be at least the stop wait; a shorter one would give up before the stop it guards
        // has had as much time as the teardown already allowed it.
        assertTrue("handover=" + handover + "ms stop=" + bridgeStop + "ms", handover >= bridgeStop);
        // And still bounded, so a wedged worker fails the connect attempt instead of stalling it.
        assertTrue("handover=" + handover + "ms", handover <= 15_000L);
    }

    private static int mtu(String protocol, int configured) {
        return AetherVpnService.effectiveMtu(protocol, configured);
    }
}
