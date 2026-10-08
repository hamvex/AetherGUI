package com.firstham.aethergui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionDefaultsTest {
    @Test public void noizeModesPreserveFirewallGfwAndEverySavedIndex() {
        String[] modes = {"firewall", "gfw", "balanced", "aggressive", "off", "light"};
        for (int index = 0; index < modes.length; index++) assertEquals(modes[index], VpnConnectionController.obfuscationMode(index));
        assertEquals(ConnectionDefaults.OBFUSCATION, VpnConnectionController.obfuscationMode(-1));
        assertEquals(ConnectionDefaults.OBFUSCATION, VpnConnectionController.obfuscationMode(modes.length));
    }

    @Test public void freshInstallUsesTurboWireGuardPsiphonDefaultAutomaticMtuAndHttp2() {
        // dev.020 CHANGE 1: the fresh/reset default protocol is WireGuard + Psiphon (storage
        // index 5), an explicit product decision that deliberately overrides the dev.019
        // benchmark winner (MASQUE + Psiphon) — the benchmark measured all candidates 5/5, so
        // the switch is product intent, not a performance correction. The chain state it
        // implies is established by PrivacyChainState on first run / reset. An existing user's
        // saved protocol is never overwritten by an upgrade.
        assertEquals(5, ConnectionDefaults.PROTOCOL_INDEX);
        assertEquals("wg", ConnectionDefaults.PROTOCOL);
        assertEquals(5, PsiphonChainRouting.combinedProtocolIndex(1));
        assertEquals(1, PsiphonChainRouting.combinedBaseIndex(5));
        assertEquals(1, ConnectionDefaults.SCAN_INDEX);
        assertEquals("turbo", ConnectionDefaults.SCAN);
        assertEquals("turbo", VpnConnectionController.scanMode(ConnectionDefaults.SCAN_INDEX));
        assertEquals(2, ConnectionDefaults.OBFUSCATION_INDEX);
        assertEquals("balanced", ConnectionDefaults.OBFUSCATION);
        // MASQUE defaults to HTTP/2. Index 1 must stay aligned with R.array.transport_labels, whose
        // second entry is HTTP/2; the array order is deliberately unchanged so stored indices keep
        // their meaning across the upgrade.
        assertEquals(1, ConnectionDefaults.TRANSPORT_INDEX);
        assertEquals("h2", ConnectionDefaults.TRANSPORT);
        assertEquals("automatic", ConnectionDefaults.MTU_MODE);
        // The default must survive normalisation, or the connect intent would silently say "manual".
        assertEquals("automatic", VpnConnectionController.normalizedMtuMode(ConnectionDefaults.MTU_MODE));
    }

    @Test public void freshDefaultProtocolStateIsValidAndConnectable() {
        // The complete state the fresh install / Reset Defaults writes for the combined default:
        // the Psiphon chain active on the WireGuard base, every Tor-chain field explicitly off,
        // and validation clean.
        java.util.Map<String, Object> values = CoreSettings.values(new java.util.LinkedHashMap<>());
        values.put("protocol", 1);
        values.put("psiphonMode", "chain");
        values.put("torProxy", false);
        values.put("torMode", "side");
        org.junit.Assert.assertNull(PrivacySettings.invalid(values, "wg"));
        org.junit.Assert.assertNull(PrivacySettings.unavailable(values));
        assertTrue(PsiphonChainRouting.chainActive(values));
    }

    @Test public void savedScanIndicesRetainTheirSlotsAndStealthMigratesToVerified() {
        String[] storedModes = {"balanced", "turbo", "thorough", "verified", "ironclad"};
        for (int index = 0; index < storedModes.length; index++) {
            assertEquals(storedModes[index], VpnConnectionController.scanMode(index));
        }
        assertEquals("turbo", VpnConnectionController.scanMode(-1));
        assertEquals("turbo", VpnConnectionController.scanMode(5));
    }

    @Test public void legacyScanAliasesUseTheOfficialVerifiedReplacement() {
        for (String alias : new String[]{"stealth", "quiet", "proven", " VERIFIED "}) {
            assertEquals("verified", ConnectionDefaults.normalizedScanMode(alias));
        }
        for (String mode : new String[]{"turbo", "balanced", "thorough", "ironclad"}) {
            assertEquals(mode, ConnectionDefaults.normalizedScanMode(mode));
        }
        assertEquals(ConnectionDefaults.SCAN, ConnectionDefaults.normalizedScanMode(null));
    }

    @Test public void activeStatesCanBeStoppedFromTile() {
        assertTrue(VpnConnectionController.canDisconnect("connected"));
        assertTrue(VpnConnectionController.canDisconnect("reconnecting"));
        assertTrue(VpnConnectionController.canDisconnect("smart-testing"));
        assertFalse(VpnConnectionController.canDisconnect("disconnected"));
        assertFalse(VpnConnectionController.canDisconnect("error"));
    }

    @Test public void autoConnectOnlyStartsAnIdleLaunch() {
        assertTrue(VpnConnectionController.shouldAutoConnect(true, "disconnected"));
        assertTrue(VpnConnectionController.shouldAutoConnect(true, "error"));
        assertFalse(VpnConnectionController.shouldAutoConnect(false, "disconnected"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "checking"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "starting"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "connected"));
    }

    @Test public void legacySmartModeMigratesToSmartProtocol() {
        assertEquals("vpn", VpnConnectionController.normalizedMode("smart"));
        assertEquals(3, VpnConnectionController.normalizedProtocolIndex("smart", 2));
        assertEquals("manual", VpnConnectionController.normalizedMode("manual"));
        assertEquals(1, VpnConnectionController.normalizedProtocolIndex("vpn", 1));
    }
}
