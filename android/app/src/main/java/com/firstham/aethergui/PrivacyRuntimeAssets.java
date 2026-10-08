package com.firstham.aethergui;

import android.content.ContentResolver;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

final class PrivacyRuntimeAssets {
    static Map<String, String> prepare(ContentResolver resolver, File nativeDirectory, File privateDirectory,
                                       String abi, Map<String, ?> settings) throws Exception {
        Map<String, Object> values = CoreSettings.values(settings);
        // Check if current configuration uses any unavailable features
        // Per-mode checking allows available modes (Chain Auto/Direct) while blocking unavailable ones
        String unavailable = PrivacySettings.unavailable(values);
        if (unavailable != null) throw new IllegalArgumentException("Selected privacy feature unavailable: " + unavailable);
        Map<String, String> environment = new LinkedHashMap<>();
        if (CoreSettings.enabled(values, "torProxy")) {
            environment.put("AETHER_TOR_DIR", new File(privateDirectory, "aether-tor").getAbsolutePath());
            if (CoreSettings.string(values, "torBridgeMode").equals("file")) {
                File helper = new File(nativeDirectory, NativeIntegrity.LYREBIRD);
                NativeIntegrity.verify(helper, abi);
                File bridge = BridgeDocuments.prepare(resolver, CoreSettings.string(values, "torBridgeUri"), new File(privateDirectory, "bridge-inputs"));
                environment.put("AETHER_TOR_BRIDGE_FILE", bridge.getAbsolutePath());
                environment.put("AETHER_TOR_PT", "obfs4=" + helper.getAbsolutePath() + ";snowflake=" + helper.getAbsolutePath()
                        + ";webtunnel=" + helper.getAbsolutePath() + ";meek_lite=" + helper.getAbsolutePath()
                        + ";obfs3=" + helper.getAbsolutePath() + ";scramblesuit=" + helper.getAbsolutePath());
            }
        }
        if (!CoreSettings.string(values, "psiphonMode").equals("off")) {
            File helper = new File(nativeDirectory, NativeIntegrity.PSIPHON);
            NativeIntegrity.verify(helper, abi);
            environment.put("AETHER_PSIPHON_BIN", helper.getAbsolutePath());
            String team = CoreSettings.string(values, "team");
            if (!team.isEmpty() && !team.matches("[A-Za-z0-9_-]{1,63}")) throw new IllegalArgumentException("Invalid organization");
            environment.put("AETHER_PSIPHON_DIR", new File(privateDirectory, team.isEmpty() ? "aether-psiphon" : "aether-team-" + team.toLowerCase(java.util.Locale.ROOT) + "-psiphon").getAbsolutePath());
        }
        return environment;
    }

    // dev.034: the manual SSL_CERT_DIR export is REMOVED. Aether Core v2.3.0 finds the
    // Android CA stores itself (psiphon.rs ca_store_env(): /apex/com.android.conscrypt/
    // cacerts and /system/etc/security/cacerts, plus the Termux/Linux bundle paths) and
    // exports SSL_CERT_DIR/SSL_CERT_FILE for the helper only when neither variable is
    // already set. The old app-side injection would have silently disabled that native
    // detection (a pre-set variable wins). CoreSettings.clearInheritedEnvironment still
    // strips any inherited SSL_* variable before the Core's own environment is applied,
    // so the native path governs. Physical dev.034 validation exercises this deliberately.

    private PrivacyRuntimeAssets() { }
}
