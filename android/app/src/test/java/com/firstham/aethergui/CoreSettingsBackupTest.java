package com.firstham.aethergui;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CoreSettingsBackupTest {
    @Test public void oldBackupDefaultsNewOptionsWithoutChangingExistingValues() {
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("psiphonMode", "chain"); saved.put("accessToken", "private-token"); saved.put("team", "example");
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("torProxy", true); backup.put("wgKeepalive", 11);
        Map<String, Object> restored = CoreSettingsBackup.restoreValues(saved, backup);
        assertEquals("off", restored.get("psiphonMode"));
        assertEquals(1, restored.get("coreSettingsSchema"));
        assertEquals(true, restored.get("torProxy"));
        assertEquals(11, restored.get("wgKeepalive"));
        assertEquals("private-token", restored.get("accessToken"));
        assertEquals("chain", saved.get("psiphonMode"));
    }

    @Test public void newBackupRoundTripPreservesDormantSettingsNotLocalGrantsOrSecrets() {
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("psiphonTransport", "cdn"); saved.put("psiphonRegion", "DE");
        saved.put("torBridgeMode", "file"); saved.put("torBridgeUri", "content://documents/bridge");
        saved.put("accessClientSecret", "private-secret");
        Map<String, Object> exported = CoreSettingsBackup.exportValues(saved);
        assertFalse(exported.containsKey("torBridgeUri"));
        assertFalse(exported.containsKey("accessClientSecret"));
        assertEquals("off", exported.get("torBridgeMode"));
        Map<String, Object> restored = CoreSettingsBackup.restoreValues(new LinkedHashMap<>(), exported);
        assertEquals("cdn", restored.get("psiphonTransport"));
        assertEquals("DE", restored.get("psiphonRegion"));
        assertEquals("", restored.get("torBridgeUri"));
    }

    @Test public void unknownSchemasAndWrongTypesFailBeforeAnySavedMutation() {
        Map<String, Object> saved = new LinkedHashMap<>(); saved.put("torProxy", true);
        Map<String, Object> backup = new LinkedHashMap<>(); backup.put("coreSettingsSchema", 2);
        assertThrows(IllegalArgumentException.class, () -> CoreSettingsBackup.restoreValues(saved, backup));
        backup.put("coreSettingsSchema", 1); backup.put("psiphonHttp", "true");
        assertThrows(IllegalArgumentException.class, () -> CoreSettingsBackup.restoreValues(saved, backup));
        assertEquals(1, saved.size());
    }

    @Test public void importCannotOverwriteLocalCredentialsOrCarryUnapprovedEnvironment() {
        Map<String, Object> saved = new LinkedHashMap<>(); saved.put("upstreamProxy", "socks5://127.0.0.1:1080");
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("upstreamProxy", "https://attacker.invalid"); backup.put("AETHER_PSIPHON_BIN", "/unapproved");
        Map<String, Object> restored = CoreSettingsBackup.restoreValues(saved, backup);
        assertEquals(saved.get("upstreamProxy"), restored.get("upstreamProxy"));
        assertFalse(restored.containsKey("AETHER_PSIPHON_BIN"));
    }

    @Test public void organizationChangeCannotReuseOrEraseAnotherTeamsCredentials() {
        Map<String, Object> saved = CoreSettings.values(new LinkedHashMap<>());
        saved.put("team", "first-team"); saved.put("accessToken", "first-token");
        Map<String, Object> backup = new LinkedHashMap<>(); backup.put("team", "second-team");
        assertThrows(IllegalArgumentException.class, () -> CoreSettingsBackup.restoreValues(saved, backup));
        assertEquals("first-token", saved.get("accessToken"));
        assertEquals("first-team", saved.get("team"));
    }
}
