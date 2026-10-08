package com.firstham.aethergui;

import android.content.SharedPreferences;

import java.util.Map;

/**
 * Single authority for the persisted privacy-chain state (dev.019 ISSUE 1).
 *
 * <p>dev.018's defect: selecting a combined protocol entry wrote the state for the chain being
 * {@em entered} but never atomically cleared the state for the chain being {@em left}. The exact
 * failing sequence: "WireGuard + Tor" wrote {@code torProxy=true, torMode=chain}; selecting
 * "WireGuard + Psiphon" then wrote {@code psiphonMode=chain, torMode=side} but left
 * {@code torProxy=true}. {@code PrivacySettings.invalid()}'s mutual-exclusion guard
 * ({@code psiphonMode != "off" && torProxy}) rejected every following connect with only the
 * generic "advanced setting" message, and only Reset Defaults or Clear Data repaired it.
 *
 * <p>Every transition below applies a complete, explicit state — both chains and the Tor switch —
 * in ONE {@code SharedPreferences} commit, so no residual hidden preference can ever block an
 * unrelated protocol. Plain protocols keep the Tor side-switch meaning (the separate 1821 proxy
 * is a standalone feature the user may have chosen directly), so a plain selection only clears
 * the chain fields, never the side-proxy preference.
 */
final class PrivacyChainState {
    /** Whether the persisted state has a full-device chain selected (either family). */
    static boolean chainSelected(SharedPreferences preferences) {
        return "chain".equals(preferences.getString("psiphonMode", "off"))
                || TorChainRouting.chainActive(CoreSettings.values(preferences.getAll()));
    }

    /** Tor chain state for diagnostics: which family is active, and why. */
    static String describe(SharedPreferences preferences) {
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        String psiphon = CoreSettings.string(values, "psiphonMode");
        boolean torProxy = CoreSettings.enabled(values, "torProxy");
        String torMode = CoreSettings.string(values, "torMode");
        return "psiphonMode=" + psiphon + " torProxy=" + torProxy + " torMode=" + torMode;
    }

    /**
     * A + Psiphon selection: activates the Psiphon chain and fully deactivates every Tor-chain
     * field ({@code torProxy=false} is the critical fix — dev.018 left it true and the
     * mutual-exclusion guard rejected all subsequent connections).
     */
    static void selectPsiphonChain(SharedPreferences preferences, int baseProtocolIndex) {
        preferences.edit()
                .putInt("protocol", baseProtocolIndex)
                .putString("psiphonMode", "chain")
                .putBoolean("torProxy", false)
                .putString("torMode", "side")
                .apply();
    }

    /**
     * A + Tor selection: activates the Tor chain and fully deactivates the Psiphon chain
     * ({@code psiphonMode="off"}); the Tor switch itself is on by definition of the entry.
     */
    static void selectTorChain(SharedPreferences preferences, int baseProtocolIndex) {
        preferences.edit()
                .putInt("protocol", baseProtocolIndex)
                .putString("psiphonMode", "off")
                .putBoolean("torProxy", true)
                .putString("torMode", "chain")
                .apply();
    }

    /**
     * A plain protocol selection: no privacy chain may stay active, but the Tor side-proxy
     * preference keeps its own meaning (it only ever mattered when the user set it directly),
     * so exactly the chain fields are cleared and {@code torProxy} is preserved as-is.
     */
    static void selectPlainProtocol(SharedPreferences preferences, int baseProtocolIndex) {
        preferences.edit()
                .putInt("protocol", baseProtocolIndex)
                .putString("psiphonMode", "off")
                .putString("torMode", "side")
                .apply();
    }

    private PrivacyChainState() { }
}
