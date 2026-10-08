package com.firstham.aethergui;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

final class BridgeDocuments {
    static void persistSelection(ContentResolver resolver, Uri uri, int resultFlags) throws IOException {
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (uri == null || !PrivacySettings.documentUri(uri.toString()) || (resultFlags & required) != required)
            throw new IOException("A persistent read-only document grant is required");
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Bridge document unavailable");
            BridgeFile.read(input);
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static File prepare(ContentResolver resolver, String documentUri, File directory) throws Exception {
        if (!PrivacySettings.documentUri(documentUri)) throw new IOException("Invalid bridge document reference");
        Uri uri = Uri.parse(documentUri);
        boolean permitted = false;
        for (UriPermission permission : resolver.getPersistedUriPermissions())
            if (permission.isReadPermission() && permission.getUri().equals(uri)) permitted = true;
        if (!permitted) throw new IOException("Bridge document permission is missing or revoked");
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Bridge document unavailable");
            bytes = BridgeFile.read(input);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        Files.createDirectories(directory.toPath());
        File destination = new File(directory, "bridges-" + hash + ".txt");
        if (!destination.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) throw new IOException("Invalid bridge destination");
        if (!destination.exists()) Files.write(destination.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (!Arrays.equals(bytes, Files.readAllBytes(destination.toPath()))) throw new IOException("Private bridge copy changed");
        return destination;
    }

    private BridgeDocuments() { }
}
