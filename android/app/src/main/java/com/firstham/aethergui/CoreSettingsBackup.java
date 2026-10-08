package com.firstham.aethergui;

import java.util.LinkedHashMap;
import java.util.Map;

final class CoreSettingsBackup {
    static boolean localOnly(String key) {
        return key.equals("accessToken") || key.equals("accessClientId") || key.equals("accessClientSecret")
                || key.equals("accessEmail") || key.equals("upstreamProxy") || key.equals("torBridgeUri");
    }

    static Map<String, Object> exportValues(Map<String, ?> saved) {
        Map<String, Object> portable = CoreSettings.values(saved);
        portable.keySet().removeIf(CoreSettingsBackup::localOnly);
        portable.put("torBridgeMode", "off");
        return portable;
    }

    static Map<String, Object> restoreValues(Map<String, ?> saved, Map<String, ?> portable) {
        Map<String, Object> restored = CoreSettings.values(saved);
        if (!portable.containsKey("coreSettingsSchema")) restored.putAll(PrivacySettings.DEFAULTS);
        for (String key : CoreSettings.DEFAULTS.keySet()) {
            if (!portable.containsKey(key) || localOnly(key)) continue;
            Object value = portable.get(key);
            if (!CoreSettings.DEFAULTS.get(key).getClass().isInstance(value))
                throw new IllegalArgumentException("Invalid backup field: " + key);
            restored.put(key, value);
        }
        restored.put("torBridgeMode", "off");
        restored.put("torBridgeUri", "");
        if (!CoreSettings.string(saved, "team").equalsIgnoreCase(CoreSettings.string(restored, "team"))) {
            for (String key : new String[]{"accessToken", "accessClientId", "accessClientSecret"})
                if (!CoreSettings.string(saved, key).isEmpty())
                    throw new IllegalArgumentException("Clear current organization credentials before restoring another organization");
        }
        String invalid = CoreSettings.invalid(restored, null, "h2");
        if (invalid != null && !invalid.equals("accessToken") && !invalid.equals("accessClientId") && !invalid.equals("accessClientSecret"))
            throw new IllegalArgumentException("Invalid backup Core field: " + invalid);
        String privacyInvalid = PrivacySettings.invalid(restored, null);
        if (privacyInvalid != null) throw new IllegalArgumentException("Invalid backup privacy field: " + privacyInvalid);
        return new LinkedHashMap<>(restored);
    }

    private CoreSettingsBackup() { }
}
