package com.firstham.aethergui;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BridgeFileTest {
    private static final String BRIDGE = "obfs4 198.51.100.1:443 0123456789012345678901234567890123456789 cert=fixture iat-mode=0";
    private byte[] read(String text) throws IOException { return BridgeFile.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }

    @Test public void boundedDocumentSupportsCommentsAndBridgePrefix() throws Exception {
        String text = "# fixture only\r\nBridge " + BRIDGE + "\r\n";
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), read(text));
    }

    @Test public void rejectsEmptyUnsafeUnsupportedAndOversizedDocuments() {
        for (String text : new String[]{"# only comments", "", "obfs4", BRIDGE + "\u0000", BRIDGE.replace("obfs4", "unknown"), BRIDGE.replace("198.51.100.1:443", "example.com:443"), BRIDGE.replace("0123456789012345678901234567890123456789", "invalid"), "x".repeat(BridgeFile.MAX_BYTES + 1), (BRIDGE + "\n").repeat(129)})
            assertThrows(IOException.class, () -> read(text));
    }

    @Test public void rejectsInvalidUtf8AndHonorsCancellation() {
        assertThrows(IOException.class, () -> BridgeFile.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28})));
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class, () -> read(BRIDGE)); }
        finally { Thread.interrupted(); }
    }

    @Test public void acceptsOnlyDocumentUrisNotFilesystemOrNetworkLocations() {
        assertTrue(PrivacySettings.documentUri("content://documents/tree/id/document/bridge"));
        for (String value : new String[]{"file:///sdcard/bridge", "https://example.com/bridge", "content:///bridge", "content://documents/bridge#fragment", "/private/file"})
            assertFalse(value, PrivacySettings.documentUri(value));
    }
}
