package com.firstham.aethergui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionDefaultsTest {
    @Test public void freshInstallUsesTurboAndGool() {
        assertEquals(2, ConnectionDefaults.PROTOCOL_INDEX);
        assertEquals("gool", ConnectionDefaults.PROTOCOL);
        assertEquals(1, ConnectionDefaults.SCAN_INDEX);
        assertEquals("turbo", ConnectionDefaults.SCAN);
    }

    @Test public void activeStatesCanBeStoppedFromTile() {
        assertTrue(VpnConnectionController.canDisconnect("connected"));
        assertTrue(VpnConnectionController.canDisconnect("reconnecting"));
        assertTrue(VpnConnectionController.canDisconnect("smart-testing"));
        assertFalse(VpnConnectionController.canDisconnect("disconnected"));
        assertFalse(VpnConnectionController.canDisconnect("error"));
    }
}
