package com.firstham.aethergui;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Checks the executable bytes before every launch, using build-time release pins. */
final class CoreIntegrity {
    static String verify(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] header = new byte[20];
        try (FileInputStream stream = new FileInputStream(file)) {
            if (stream.read(header) != header.length || header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[5] != 1)
                throw new IllegalArgumentException("Aether Core integrity error: invalid ELF");
            digest.update(header);
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        int machine = (header[18] & 255) | ((header[19] & 255) << 8);
        String expected = machine == 183 && header[4] == 2 ? BuildConfig.AETHER_ARM64_SHA256
                : machine == 40 && header[4] == 1 ? BuildConfig.AETHER_ARMV7_SHA256
                : machine == 62 && header[4] == 2 ? BuildConfig.AETHER_X86_64_SHA256 : "";
        StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!actual.toString().equals(expected)) throw new IllegalArgumentException("Aether Core integrity error: SHA-256 mismatch");
        return machine == 183 ? "arm64-v8a" : machine == 40 ? "armeabi-v7a" : "x86_64";
    }
    private CoreIntegrity() { }
}
