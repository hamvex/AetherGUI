package com.firstham.aethergui;

final class UpdateInfo {
    final String version;
    final String notes;
    final String downloadUrl;
    final String checksum;

    UpdateInfo(String version, String notes, String downloadUrl, String checksum) {
        this.version = version;
        this.notes = notes == null ? "" : notes;
        this.downloadUrl = downloadUrl;
        this.checksum = checksum == null ? "" : checksum.toLowerCase(java.util.Locale.US);
    }
}
