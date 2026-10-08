package com.firstham.aethergui;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class NativeIntegrity {
    static final String HEV = "libhev-socks5-tunnel.so";
    static final String PSIPHON = "libpsiphon-tunnel-core.so";
    static final String LYREBIRD = "liblyrebird.so";

    static void verify(File file, String abi) throws Exception {
        int index = "arm64-v8a".equals(abi) ? 0 : "armeabi-v7a".equals(abi) ? 1 : "x86_64".equals(abi) ? 2 : -1;
        if (index < 0) throw new IllegalArgumentException("Unsupported native ABI");
        String[] hashes;
        if (HEV.equals(file.getName())) hashes = new String[]{BuildConfig.HEV_ARM64_SHA256, BuildConfig.HEV_ARMV7_SHA256, BuildConfig.HEV_X86_64_SHA256};
        else if (PSIPHON.equals(file.getName())) hashes = new String[]{BuildConfig.PSIPHON_ARM64_SHA256, BuildConfig.PSIPHON_ARMV7_SHA256, BuildConfig.PSIPHON_X86_64_SHA256};
        else if (LYREBIRD.equals(file.getName())) hashes = new String[]{BuildConfig.LYREBIRD_ARM64_SHA256, BuildConfig.LYREBIRD_ARMV7_SHA256, BuildConfig.LYREBIRD_X86_64_SHA256};
        else throw new IllegalArgumentException("Unapproved native library");
        verify(file, hashes[index], index == 1 ? 1 : 2, new int[]{183, 40, 62}[index], HEV.equals(file.getName()) ? 3 : 2);
    }

    static void verify(File file, String expected, int elfClass, int machine, int type) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) throw new IllegalArgumentException("Native integrity: truncated ELF");
                offset += count;
            }
            if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[5] != 1 ||
                    header[4] != elfClass || ((header[16] & 255) | ((header[17] & 255) << 8)) != type ||
                    ((header[18] & 255) | ((header[19] & 255) << 8)) != machine)
                throw new IllegalArgumentException("Native integrity: ELF/ABI mismatch");
            digest.update(header);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!actual.toString().equals(expected)) throw new IllegalArgumentException("Native integrity: SHA-256 mismatch");
    }

    private NativeIntegrity() { }
}
