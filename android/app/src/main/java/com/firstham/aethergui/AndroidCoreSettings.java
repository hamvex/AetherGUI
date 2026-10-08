package com.firstham.aethergui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;

final class AndroidCoreSettings {
    static Intent copyToIntent(Intent intent, SharedPreferences preferences) {
        migrate(preferences);
        for (Map.Entry<String, Object> e : CoreSettings.values(preferences.getAll()).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) intent.putExtra(e.getKey(), (Boolean)v);
            else if (v instanceof Integer) intent.putExtra(e.getKey(), (Integer)v);
            else intent.putExtra(e.getKey(), v == null ? "" : v.toString());
        }
        return intent;
    }

    @SuppressWarnings("deprecation")
    static Map<String, Object> fromIntent(Intent intent) {
        Map<String, Object> values = new LinkedHashMap<>();
        Bundle extras = intent.getExtras();
        if (extras != null) for (String key : CoreSettings.DEFAULTS.keySet()) if (extras.containsKey(key)) values.put(key, extras.get(key));
        return CoreSettings.values(values);
    }

    static void store(SharedPreferences.Editor edit, Map<String, ?> values) {
        for (String key : CoreSettings.DEFAULTS.keySet()) {
            if (values.containsKey(key) && !CoreSettings.DEFAULTS.get(key).getClass().isInstance(values.get(key)))
                throw new IllegalArgumentException("Invalid Core setting type: " + key);
        }
        for (String key : CoreSettings.DEFAULTS.keySet()) {
            Object value = values.get(key);
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value);
            else if (value instanceof String) edit.putString(key, (String)value);
        }
    }
    static boolean secret(String key) {
        return CoreSettingsBackup.localOnly(key);
    }
    static void migrate(SharedPreferences preferences) {
        Map<String, ?> saved = preferences.getAll();
        Object schema = saved.get("coreSettingsSchema");
        if (schema != null && !Integer.valueOf(PrivacySettings.SCHEMA).equals(schema))
            throw new IllegalArgumentException("Unsupported Core settings schema");
        Map<String, Object> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : PrivacySettings.DEFAULTS.entrySet())
            if (!saved.containsKey(entry.getKey())) missing.put(entry.getKey(), entry.getValue());
        if (!missing.isEmpty()) {
            SharedPreferences.Editor edit = preferences.edit();
            store(edit, missing);
            if (!edit.commit()) throw new IllegalStateException("Core settings migration could not be saved");
        }
        migrateGoolTopology(preferences);
        migrateFragmentSizeDefault(preferences);
        repairLegacyChainConflict(preferences);
    }

    /**
     * dev.034 Gool migration (PROMPT §26.1): dev.020's Gool was the legacy WARP-in-WARP
     * topology, and Aether Core v2.3.0's plain `gool` now runs the new MASQUE-carried Gool.
     * A user whose SAVED protocol is a Gool entry (storage indices 2/6/9: gool, gool+Psiphon,
     * gool+Tor) keeps the legacy semantics: the missing `goolMode` key is written as
     * "classic" exactly once, before the first v2.3.0 connect, so the upgrade never silently
     * changes the running topology. Fresh installs (and users not on a Gool entry) receive
     * the new-product default "masque" through CoreSettings.DEFAULTS. An explicit stored
     * choice is never rewritten.
     */
    private static void migrateGoolTopology(SharedPreferences preferences) {
        Map<String, ?> saved = preferences.getAll();
        if (saved.containsKey("goolMode")) return;
        int protocol = preferences.getInt("protocol", -1);
        if (protocol != 2 && protocol != 6 && protocol != 9) return;
        preferences.edit().putString("goolMode", "classic").apply();
    }

    /**
     * dev.034 fragment-size default migration: the exact v2.3.0 upstream default changed
     * from 16-32 (v2.1.0) to 8-16. A dev.020 user whose stored value equals the old
     * default (the value the old UI displayed and saved for an untouched field) is moved
     * to the new upstream default exactly once; any other stored value is an explicit
     * user choice and is never rewritten, and a later explicit 16-32 selection stays
     * because the marker makes this a strictly one-time conversion.
     */
    private static void migrateFragmentSizeDefault(SharedPreferences preferences) {
        Map<String, ?> saved = preferences.getAll();
        if (Boolean.TRUE.equals(saved.get("fragmentSizeV230"))) return;
        SharedPreferences.Editor edit = preferences.edit();
        if ("16-32".equals(saved.get("h2FragmentSize"))) edit.putString("h2FragmentSize", "8-16");
        edit.putBoolean("fragmentSizeV230", true);
        edit.apply();
    }

    /**
     * dev.019 ISSUE 1 data-preserving migration: dev.018 and earlier could leave the exact
     * conflicted state the Tor → Psiphon transition produced ({@code psiphonMode=chain} with
     * {@code torProxy=true}, or an active chain with an unsupported base protocol like smart/mim).
     * Upgrading users hit the generic validation rejection on their very first connect. The
     * repair keeps every unrelated preference: it resolves the conflict toward the entry the user
     * last selected (the chain whose fields are consistent) and deactivates the other. Schema is
     * the same, so this is purely a value repair, never a wipe.
     */
    private static void repairLegacyChainConflict(SharedPreferences preferences) {
        Map<String, ?> saved = preferences.getAll();
        Map<String, Object> values = CoreSettings.values(saved);
        String psiphonMode = CoreSettings.string(values, "psiphonMode");
        boolean torChainActive = TorChainRouting.chainActive(values);
        String protocol = new String[]{"masque", "wg", "gool", "smart", "mim"}[
                Math.max(0, Math.min(4, preferences.getInt("protocol", 2)))];
        boolean psiphonChainSupported = "wg".equals(protocol) || "gool".equals(protocol) || "masque".equals(protocol);
        SharedPreferences.Editor edit = preferences.edit();
        boolean changed = false;
        if ("chain".equals(psiphonMode) && torChainActive) {
            // Both chain families claim the exit; keep the Tor chain (its fields are fully
            // specified: torProxy + torMode=chain) and turn Psiphon off — matches what selecting
            // a + Tor entry atomically writes.
            edit.putString("psiphonMode", "off");
            changed = true;
        } else if ("chain".equals(psiphonMode) && !psiphonChainSupported) {
            // Smart Connect / MIM cannot carry a chain; deactivate it exactly as the UI's
            // smart-exclusion rule already does.
            edit.putString("psiphonMode", "off");
            changed = true;
        } else if (torChainActive && !psiphonChainSupported) {
            edit.putString("torMode", "side");
            edit.putBoolean("torProxy", false);
            changed = true;
        } else if ("chain".equals(psiphonMode) && Boolean.TRUE.equals(saved.get("torProxy"))) {
            // The exact dev.018 defect state: a + Psiphon selection with a residual torProxy=true.
            // The user's last selection was the Psiphon entry, so keep it and clear the Tor switch.
            edit.putBoolean("torProxy", false);
            changed = true;
        }
        if (changed) edit.apply();
    }
    private AndroidCoreSettings() { }
}
