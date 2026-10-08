package com.firstham.aethergui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class BridgeFile {
    static final int MAX_BYTES = 65536;

    static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Bridge import cancelled");
            if (output.size() + count > MAX_BYTES) throw new IOException("Bridge document too large");
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        int bridges = 0;
        for (String written : text.split("\\r?\\n")) {
            if (written.length() > 2048 || written.chars().anyMatch(value -> value < 32 && value != '\t'))
                throw new IOException("Invalid bridge line");
            String line = written.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            line = line.replaceFirst("(?i)^Bridge\\s+", "");
            String[] fields = line.split("\\s+");
            int address = CoreSettings.endpoint(fields[0], false) ? 0 : 1;
            if (address == 1 && !Arrays.asList("obfs4", "snowflake", "webtunnel", "meek_lite", "obfs3", "scramblesuit").contains(fields[0]))
                throw new IOException("Unsupported bridge transport");
            if (fields.length < address + 2 || !CoreSettings.endpoint(fields[address], false) || !fields[address + 1].matches("[A-Fa-f0-9]{40}"))
                throw new IOException("Invalid bridge address or identity");
            for (int index = address + 2; index < fields.length; index++)
                if (!fields[index].matches("[A-Za-z0-9_-]+=[^\\s]+")) throw new IOException("Invalid bridge option");
            if (++bridges > 128) throw new IOException("Too many bridges");
        }
        if (bridges == 0) throw new IOException("Bridge document is empty");
        return bytes;
    }

    private BridgeFile() { }
}
