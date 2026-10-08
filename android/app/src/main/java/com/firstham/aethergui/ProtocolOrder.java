package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.List;

/**
 * Display order and per-mode filtering for the Protocol selector (dev.017 CHANGES 5, 7 and 13).
 *
 * <p>The persisted {@code protocol} preference stores the entry's index in
 * {@code R.array.protocol_labels}, so that array's order is a storage contract and never changes.
 * Everything user-facing — Smart Connect first, the WIW/MIM display aliases, hiding the combined
 * privacy entries while Proxy mode is active, and hiding the Tor entries until their capability is
 * verified — is expressed here as a mapping between storage indices and dropdown positions.
 *
 * <p>Storage indices: 0=MASQUE 1=WireGuard 2=gool 3=Smart Connect 4=MIM 5=WireGuard+Psiphon
 * 6=gool+Psiphon 7=MASQUE+Psiphon 8=WireGuard+Tor 9=gool+Tor 10=MASQUE+Tor.
 */
final class ProtocolOrder {
    static final int MASQUE = 0;
    static final int WIREGUARD = 1;
    static final int GOOL = 2;
    static final int SMART = 3;
    static final int MIM = 4;
    private static final int PSIPHON_FIRST = 5;
    private static final int TOR_FIRST = 8;

    /**
     * Storage indices in dropdown display order for the given mode.
     *
     * @param proxyMode Proxy mode hides every combined privacy entry (CHANGE 5): Proxy mode is for
     *        external applications chaining through Aethon's own endpoints, so the internal
     *        full-device Psiphon/Tor combinations must not appear.
     * @param torChainAvailable gates the combined + Tor entries (CHANGE 7): they are only offered
     *        once the full-device Tor chain capability is verified.
     */
    static List<Integer> displayIndices(boolean proxyMode, boolean torChainAvailable) {
        List<Integer> order = new ArrayList<>();
        // CHANGE 13: Smart Connect is the first displayed option.
        order.add(SMART);
        order.add(MASQUE);
        order.add(WIREGUARD);
        order.add(GOOL);
        order.add(MIM);
        if (!proxyMode) {
            for (int index = PSIPHON_FIRST; index < PSIPHON_FIRST + 3; index++) order.add(index);
            if (torChainAvailable) for (int index = TOR_FIRST; index < TOR_FIRST + 3; index++) order.add(index);
        }
        return order;
    }

    /**
     * Whether the MASQUE connection method row applies to the given storage index
     * (dev.020 CHANGE 3). The persisted "protocol" preference stores the BASE protocol, and the
     * transport setting genuinely controls every MASQUE-transport protocol: plain MASQUE, MIM
     * (CoreSettings.masque() covers mim - AETHER_MASQUE_HTTP2 applies to it), and the combined
     * entries standing for a MASQUE base (7 = MASQUE+Psiphon, 10 = MASQUE+Tor). WireGuard, gool,
     * Smart Connect and their combined entries never show the row.
     */
    static boolean masqueTransportApplicable(int storageIndex) {
        int base = baseIndex(storageIndex);
        return base == MASQUE || base == MIM;
    }

    /** Whether the storage index is one of the combined + Psiphon entries. */
    static boolean isPsiphonCombined(int storageIndex) {
        return storageIndex >= PSIPHON_FIRST && storageIndex < PSIPHON_FIRST + 3;
    }

    /** Whether the storage index is one of the combined + Tor entries. */
    static boolean isTorCombined(int storageIndex) {
        return storageIndex >= TOR_FIRST && storageIndex < TOR_FIRST + 3;
    }

    /**
     * The base protocol index a storage index stands for: a combined privacy entry (5-10) maps to
     * its base (WireGuard/gool/MASQUE), a plain entry clamps to 0-4. This is the single mapping
     * every consumer — persistence, settings validation and the service contract — must use;
     * dev.017 shipped a defect where the combined entries were CLAMPED instead of mapped during
     * UI validation, which blocked connecting any + Tor entry (found in physical validation;
     * fixed in dev.018).
     */
    static int baseIndex(int storageIndex) {
        int psiphon = PsiphonChainRouting.combinedBaseIndex(storageIndex);
        if (psiphon >= 0) return psiphon;
        int tor = TorChainRouting.combinedTorBaseIndex(storageIndex);
        if (tor >= 0) return tor;
        return Math.max(0, Math.min(4, storageIndex));
    }

    private ProtocolOrder() { }
}
