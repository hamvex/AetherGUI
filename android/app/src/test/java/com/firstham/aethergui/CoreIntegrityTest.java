package com.firstham.aethergui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CoreIntegrityTest {
    @Test public void officialPayloadsPassTheRuntimeValidatorForEveryAbi() throws Exception {
        assertEquals("v2.3.0", BuildConfig.AETHER_VERSION);
        for (String abi : new String[]{"arm64-v8a", "armeabi-v7a", "x86_64"}) {
            assertEquals(abi, CoreIntegrity.verify(new File(System.getProperty("aethon.nativeInputs"), abi + "/libaether.so")));
        }
    }

    @Test public void aCorruptedOfficialExecutableIsRejected() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of(System.getProperty("aethon.nativeInputs"), "arm64-v8a/libaether.so"));
        payload[payload.length - 1] ^= 1;
        Path temporary = Files.createTempFile("aether-corrupted-", ".so");
        try {
            Files.write(temporary, payload);
            try {
                CoreIntegrity.verify(temporary.toFile());
                fail("Modified executable must not launch");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("SHA-256 mismatch"));
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Test public void invalidElfIsRejectedBeforeLaunch() throws Exception {
        Path temporary = Files.createTempFile("aether-invalid-", ".so");
        try {
            Files.write(temporary, new byte[20]);
            try {
                CoreIntegrity.verify(temporary.toFile());
                fail("Invalid ELF must not launch");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("invalid ELF"));
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
