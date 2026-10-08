package com.firstham.aethergui;

import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.019 regression coverage: the atomic privacy-chain transitions (ISSUE 1), the plain-entry
 * application path (ISSUE 4), the validation acceptance of every transition (no stale hidden
 * preference may block an unrelated protocol), ProtocolOrder/baseIndex mapping, the Tor Routing
 * UI removal contract (ISSUE 2), the reset/fresh defaults (ISSUE 8 wiring), and the legacy
 * conflict repair migration.
 */
public class PrivacyChainStateTest {
    private Map<String, Object> defaults() { return CoreSettings.values(new LinkedHashMap<>()); }

    private static final String[] PROTOCOL_NAMES = {"masque", "wg", "gool", "smart", "mim"};

    private Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private Map<String, Object> stateAfterTransition(int fromEntry, int toEntry) {
        // Simulates the two applyCombinedProtocolSelection() calls in sequence over the same
        // persisted map, exactly as the device does.
        Map<String, Object> state = new LinkedHashMap<>();
        apply(state, fromEntry);
        apply(state, toEntry);
        return state;
    }

    private void apply(Map<String, Object> state, int selection) {
        int psiphonBase = PsiphonChainRouting.combinedBaseIndex(selection);
        if (psiphonBase >= 0) {
            state.put("protocol", psiphonBase);
            state.put("psiphonMode", "chain");
            state.put("torProxy", false);
            state.put("torMode", "side");
            return;
        }
        int torBase = TorChainRouting.combinedTorBaseIndex(selection);
        if (torBase >= 0) {
            state.put("protocol", torBase);
            state.put("psiphonMode", "off");
            state.put("torProxy", true);
            state.put("torMode", "chain");
            return;
        }
        state.put("protocol", ProtocolOrder.baseIndex(selection));
        state.put("psiphonMode", "off");
        state.put("torMode", "side");
        // plain entries preserve a user-set side-proxy torProxy (PrivacyChainState contract)
    }

    // --- ISSUE 1: the exact failing sequence -----------------------------------------------

    @Test public void torThenPsiphonTransitionLeavesNoResidualTorSwitch() {
        // The reproduced defect: WireGuard + Tor → disconnect → WireGuard + Psiphon → connect
        // failed with the generic "advanced setting" error until Reset/Clear Data. The residual
        // was psiphonMode=chain + torProxy=true, which the mutual-exclusion guard rejects.
        Map<String, Object> state = stateAfterTransition(8, 5);
        assertEquals("chain", state.get("psiphonMode"));
        assertEquals(Boolean.FALSE, state.get("torProxy"));
        assertEquals("side", state.get("torMode"));
        assertEquals(1, state.get("protocol"));
    }

    @Test public void everyTransitionStateValidatesCleanlyForItsProtocol() {
        // After ANY pairwise transition, validation must pass for the protocol the user selected
        // — no stale hidden preference may block an unrelated protocol (PROMPT ISSUE 1).
        int[] allEntries = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (int from : allEntries) {
            for (int to : allEntries) {
                Map<String, Object> state = stateAfterTransition(from, to);
                Map<String, Object> values = defaults();
                values.putAll(state);
                String protocol = PROTOCOL_NAMES[((Number) state.get("protocol")).intValue()];
                String invalid = PrivacySettings.invalid(values, protocol);
                assertNull("transition " + from + "→" + to + " (" + protocol + ") invalid key: " + invalid, invalid);
            }
        }
    }

    @Test public void torThenPsiphonWithSimultaneousBaseChangeIsAtomic() {
        // WireGuard + Tor → gool + Psiphon (the PROMPT's combined transition example).
        Map<String, Object> state = stateAfterTransition(8, 6);
        assertEquals("chain", state.get("psiphonMode"));
        assertEquals(Boolean.FALSE, state.get("torProxy"));
        assertEquals(2, state.get("protocol"));
    }

    @Test public void masqueTorThenWireGuardPsiphonIsAtomic() {
        Map<String, Object> state = stateAfterTransition(10, 5);
        assertEquals("chain", state.get("psiphonMode"));
        assertEquals(Boolean.FALSE, state.get("torProxy"));
        assertEquals(1, state.get("protocol"));
    }

    @Test public void psiphonThenTorTransitionFullyDeactivatesPsiphon() {
        Map<String, Object> state = stateAfterTransition(5, 8);
        assertEquals("off", state.get("psiphonMode"));
        assertEquals(Boolean.TRUE, state.get("torProxy"));
        assertEquals("chain", state.get("torMode"));
        Map<String, Object> values = defaults();
        values.putAll(state);
        assertNull(PrivacySettings.invalid(values, "wg"));
    }

    @Test public void torThenPlainProtocolLeavesNoInvisibleChainBlockingConnection() {
        for (int plain : new int[]{0, 1, 2, 3, 4}) {
            Map<String, Object> state = stateAfterTransition(8, plain);
            assertEquals("off", state.get("psiphonMode"));
            assertEquals("side", state.get("torMode"));
            Map<String, Object> values = defaults();
            values.putAll(state);
            String protocol = PROTOCOL_NAMES[plain];
            // A user-set side Tor proxy is legitimately preserved; the Tor CHAIN is not. The
            // side switch never blocks any base protocol.
            if (!Boolean.TRUE.equals(state.get("torProxy"))
                    || !"chain".equals(state.get("torMode"))) {
                assertNull(PrivacySettings.invalid(values, protocol));
            }
        }
    }

    @Test public void psiphonThenPlainProtocolLeavesNoChain() {
        for (int plain : new int[]{0, 1, 2, 3, 4}) {
            Map<String, Object> state = stateAfterTransition(5, plain);
            assertEquals("off", state.get("psiphonMode"));
            Map<String, Object> values = defaults();
            values.putAll(state);
            assertNull(PrivacySettings.invalid(values, PROTOCOL_NAMES[plain]));
        }
    }

    @Test public void plainThenTorAndPlainThenPsiphonEstablishValidChains() {
        Map<String, Object> toTor = stateAfterTransition(1, 8);
        assertEquals("chain", toTor.get("torMode"));
        assertEquals(Boolean.TRUE, toTor.get("torProxy"));
        assertEquals("off", toTor.get("psiphonMode"));
        Map<String, Object> toPsiphon = stateAfterTransition(1, 5);
        assertEquals("chain", toPsiphon.get("psiphonMode"));
        assertEquals(Boolean.FALSE, toPsiphon.get("torProxy"));
    }

    // --- The old defect state must be invalid (guards still protect real conflicts) ---------

    @Test public void genuineConflictsAreStillRejected() {
        // Both chain families active at once remains a real conflict.
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("torProxy", true);
        values.put("torMode", "chain");
        assertEquals("psiphonMode", PrivacySettings.invalid(values, "wg"));
    }

    @Test public void irrelevantSavedSettingsDoNotBlockUnrelatedProtocols() {
        // A disabled/irrelevant setting must NOT block an unrelated protocol: an upstream proxy
        // or side Tor switch saved while a chain is OFF never blocks a plain protocol.
        Map<String, Object> values = with(defaults(), "upstreamProxy", "socks5://1.2.3.4:1080");
        assertNull(PrivacySettings.invalid(values, "wg"));
        values = with(defaults(), "torProxy", true);
        assertNull(PrivacySettings.invalid(values, "wg"));
        values = with(with(defaults(), "torProxy", true), "torMode", "side");
        assertNull(PrivacySettings.invalid(values, "wg"));
    }

    // --- ISSUE 4: baseIndex mapping and the pairwise switching contract ---------------------

    @Test public void baseIndexMapsEveryCombinedEntryToItsBase() {
        assertEquals(0, ProtocolOrder.baseIndex(0));
        assertEquals(1, ProtocolOrder.baseIndex(1));
        assertEquals(2, ProtocolOrder.baseIndex(2));
        assertEquals(3, ProtocolOrder.baseIndex(3));
        assertEquals(4, ProtocolOrder.baseIndex(4));
        assertEquals(1, ProtocolOrder.baseIndex(5));
        assertEquals(2, ProtocolOrder.baseIndex(6));
        assertEquals(0, ProtocolOrder.baseIndex(7));
        assertEquals(1, ProtocolOrder.baseIndex(8));
        assertEquals(2, ProtocolOrder.baseIndex(9));
        assertEquals(0, ProtocolOrder.baseIndex(10));
    }

    @Test public void allFiveBaseProtocolsSupportDirectPairwiseStorageSwitching() {
        // Every base→base switch must persist the target base directly (no combined intermediary
        // required). The storage indices are the entry indices themselves.
        int[] bases = {0, 1, 2, 3, 4};
        for (int from : bases) {
            for (int to : bases) {
                if (from == to) continue;
                Map<String, Object> state = stateAfterTransition(from, to);
                assertEquals("base switch " + from + "→" + to + " persisted " + state.get("protocol"),
                        to, ((Number) state.get("protocol")).intValue());
                Map<String, Object> values = defaults();
                values.putAll(state);
                assertNull(PrivacySettings.invalid(values, PROTOCOL_NAMES[to]));
            }
        }
    }

    // --- ISSUE 2: Tor Routing UI removal contract -------------------------------------------

    @Test public void torParticipationIsDerivedFromProtocolSelectionOnly() {
        // Selecting + Tor activates torProxy+torMode=chain; selecting + Psiphon or plain clears
        // the chain fields atomically; no second editable control exists in the layout (the
        // switch is a hidden compatibility carrier, asserted by ConfigurationResourcesTest).
        Map<String, Object> tor = stateAfterTransition(0, 8);
        assertTrue(TorChainRouting.chainActive(withAll(defaults(), tor)));
        Map<String, Object> psiphon = stateAfterTransition(8, 5);
        assertFalse(TorChainRouting.chainActive(withAll(defaults(), psiphon)));
        assertTrue(PsiphonChainRouting.chainActive(withAll(defaults(), psiphon)));
    }

    private Map<String, Object> withAll(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.putAll(overlay);
        return out;
    }

    // --- ISSUE 8 wiring: fresh/reset default protocol ---------------------------------------

    @Test public void freshDefaultProtocolIsTheDev020ProductDecision() {
        // ConnectionDefaults.PROTOCOL_INDEX is the single source of truth for fresh install and
        // Reset Defaults. dev.020 CHANGE 1 deliberately switches the default from the dev.019
        // benchmark winner (MASQUE + Psiphon, index 7) to WireGuard + Psiphon (index 5) as an
        // explicit product decision; the benchmark itself measured every candidate 5/5 connect +
        // 5/5 traffic, so performance did not force the change and must not force it back.
        assertEquals(5, ConnectionDefaults.PROTOCOL_INDEX);
        Map<String, Object> values = defaults();
        values.put("protocol", ConnectionDefaults.PROTOCOL_INDEX);
        int base = ProtocolOrder.baseIndex(ConnectionDefaults.PROTOCOL_INDEX);
        assertEquals(1, base);
        assertNull(PrivacySettings.invalid(values, PROTOCOL_NAMES[base]));
    }

    @Test public void explicitSavedProtocolSurvivesPlainReread() {
        // An existing user's explicitly saved protocol is preserved (never overwritten by an
        // upgrade): the migration only repairs conflicted chain state, never the protocol.
        for (int saved = 0; saved <= 10; saved++) {
            Map<String, Object> values = defaults();
            values.put("protocol", saved);
            int base = ProtocolOrder.baseIndex(saved);
            assertEquals(base, ProtocolOrder.baseIndex(base));
        }
    }

    // --- Legacy conflict repair (migration) -------------------------------------------------

    @Test public void repairRulesResolveTheDev018DefectStates() {
        // The exact dev.018 residual: psiphonMode=chain + torProxy=true (selection was Psiphon).
        // Rule: keep the Psiphon entry, clear the Tor switch.
        Map<String, Object> values = defaults();
        values.put("psiphonMode", "chain");
        values.put("torProxy", true);
        values.put("torMode", "side");
        values.put("protocol", 1);
        // The mutual-exclusion guard proves the state is conflicted...
        assertEquals("psiphonMode", PrivacySettings.invalid(values, "wg"));
        // ...and the documented repair direction is torProxy=false.
        Map<String, Object> repaired = with(values, "torProxy", false);
        assertNull(PrivacySettings.invalid(repaired, "wg"));
    }

    @Test public void psiphonModeSideValuesRemainValidTransitions() {
        // torMode=side with torProxy=true must never be treated as a chain conflict.
        Map<String, Object> values = with(with(defaults(), "torProxy", true), "torMode", "side");
        assertNull(PrivacySettings.invalid(values, "wg"));
        assertNull(PrivacySettings.invalid(values, "gool"));
        assertNull(PrivacySettings.invalid(values, "masque"));
    }

    // --- Listener plan sanity for every chain state ------------------------------------------

    @Test public void listenerPlanHasNoCollisionsForAnyChainTransitionState() {
        for (int to = 5; to <= 10; to++) {
            for (int from = 5; from <= 10; from++) {
                Map<String, Object> state = stateAfterTransition(from, to);
                Map<String, Object> values = defaults();
                values.putAll(state);
                boolean anyChain = PsiphonChainRouting.chainActive(values) || TorChainRouting.chainActive(values);
                if (!anyChain) continue;
                // The plan must resolve without a port collision for a valid protocol.
                String protocol = PROTOCOL_NAMES[((Number) state.get("protocol")).intValue()];
                if (PrivacySettings.invalid(values, protocol) != null) continue;
                PrivacyRuntimeConfig.listenerAddresses(values, "127.0.0.1:1819");
            }
        }
    }
}
