package com.firstham.aethergui;

final class ConnectionDefaults {
    /**
     * Fresh-install / Reset-Defaults protocol (dev.020 CHANGE 1). This is an explicit product
     * decision: WireGuard + Psiphon replaces the dev.019 benchmark winner MASQUE + Psiphon. The
     * dev.019 physical benchmark (2026-10-02, Samsung SM-A556E, 5 cycles per candidate,
     * work/dev019-benchmark-20261002/) measured every combined mode 5/5 connect + 5/5
     * real-traffic success with clean lifecycle — including WireGuard + Psiphon at median 37.2s /
     * worst 43.1s — so the switch is a deliberate product choice, NOT a performance correction:
     * dev.020 must not flip back to MASQUE + Psiphon merely because it measured faster. The
     * value is the STORAGE index of the combined entry (5 = WireGuard + Psiphon). An existing
     * user's saved protocol is never overwritten by an upgrade — this default applies to fresh
     * installs and Reset Defaults only.
     */
    static final int PROTOCOL_INDEX = 5;
    static final String PROTOCOL = "wg";
    /**
     * Missing/reset preference default. Keep the array order stable: saved choices always win.
     * Index 1 is Turbo in both {@code R.array.scan_labels} and the controller's scan mapping.
     */
    static final int SCAN_INDEX = 1;
    static final String SCAN = "turbo";
    static final int OBFUSCATION_INDEX = 2;
    static final String OBFUSCATION = "balanced";
    static final String SMART_PROTOCOL = "smart";
    /**
     * MASQUE transport default. Index 1 of {@code R.array.transport_labels} is HTTP/2; the array
     * order is deliberately left alone so a stored index keeps meaning across the upgrade that
     * moved the default off HTTP/3. HTTP/3 stays fully supported and is used whenever the user
     * selects it - runtime evidence in security-evidence/phase15 showed HTTP/3 gateway discovery
     * failing on networks where HTTP/2 reaches the same edges, so it is no longer the default.
     */
    static final int TRANSPORT_INDEX = 1;
    static final String TRANSPORT = "h2";
    /** Automatic MTU is the fresh-install default; a stored "manual" value always wins. */
    static final String MTU_MODE = "automatic";

    static String normalizedScanMode(String requested) {
        if (requested == null) return SCAN;
        String mode = requested.trim().toLowerCase(java.util.Locale.ROOT);
        return "stealth".equals(mode) || "quiet".equals(mode) || "proven".equals(mode) ? "verified" : mode;
    }

    private ConnectionDefaults() { }
}
