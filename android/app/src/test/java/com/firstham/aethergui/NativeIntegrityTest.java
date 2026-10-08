package com.firstham.aethergui;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class NativeIntegrityTest {
    @Test public void approvedHevAndHelpersVerifyForEachAbi() throws Exception {
        for (String abi : new String[]{"arm64-v8a", "armeabi-v7a", "x86_64"})
            for (String library : new String[]{NativeIntegrity.HEV, NativeIntegrity.PSIPHON, NativeIntegrity.LYREBIRD})
                NativeIntegrity.verify(new File(System.getProperty("aethon.nativeInputs"), abi + "/" + library), abi);
    }

    @Test public void wrongAbiAndMissingInputAreRejected() {
        File hev = new File(System.getProperty("aethon.nativeInputs"), "arm64-v8a/" + NativeIntegrity.HEV);
        assertThrows(IllegalArgumentException.class, () -> NativeIntegrity.verify(hev, "x86_64"));
        assertThrows(Exception.class, () -> NativeIntegrity.verify(new File(hev.getParentFile(), "missing.so"), "arm64-v8a"));
    }

    @Test public void changedBytesFailEvenWithValidElfHeader() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("aethon.nativeInputs"), "arm64-v8a", NativeIntegrity.HEV));
        bytes[bytes.length - 1] ^= 1;
        Path temporary = Files.createTempFile("hev-corruption-", ".so");
        try {
            Files.write(temporary, bytes);
            assertThrows(IllegalArgumentException.class, () -> NativeIntegrity.verify(temporary.toFile(), BuildConfig.HEV_ARM64_SHA256, 2, 183, 3));
        } finally { Files.deleteIfExists(temporary); }
    }
}
