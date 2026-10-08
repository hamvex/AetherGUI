package com.firstham.aethergui;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AndroidCorePersistenceTest {
    private static final class Memory {
        final Map<String, Object> saved = new LinkedHashMap<>();
        boolean commitSucceeds = true;
        final SharedPreferences preferences = (SharedPreferences)Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getAll")) return new LinkedHashMap<>(saved);
                    if (method.getName().equals("edit")) return editor();
                    if (method.getName().equals("contains")) return saved.containsKey(arguments[0]);
                    if (method.getName().startsWith("get")) return saved.getOrDefault(arguments[0], arguments[1]);
                    return null;
                });

        SharedPreferences.Editor editor() {
            Map<String, Object> pending = new LinkedHashMap<>();
            return (SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, arguments) -> {
                        if (method.getName().startsWith("put")) { pending.put((String)arguments[0], arguments[1]); return proxy; }
                        if (method.getName().equals("commit")) { if (commitSucceeds) saved.putAll(pending); return commitSucceeds; }
                        if (method.getName().equals("apply")) { saved.putAll(pending); return null; }
                        return proxy;
                    });
        }
    }

    @Test public void migrationIsIdempotentAndPreservesExistingAndUnrelatedSettings() {
        Memory memory = new Memory();
        memory.saved.put("scan", 4); memory.saved.put("torProxy", true); memory.saved.put("privateIdentity", "untouched");
        AndroidCoreSettings.migrate(memory.preferences);
        Map<String, Object> first = new LinkedHashMap<>(memory.saved);
        AndroidCoreSettings.migrate(memory.preferences);
        assertEquals(first, memory.saved);
        assertEquals(4, memory.saved.get("scan"));
        assertEquals(true, memory.saved.get("torProxy"));
        assertEquals("untouched", memory.saved.get("privateIdentity"));
        assertEquals("off", memory.saved.get("psiphonMode"));
    }

    @Test public void typedSaveAndReloadUsesActualAndroidSettingsAdapter() {
        Memory memory = new Memory();
        Map<String, Object> values = CoreSettings.values(memory.saved);
        values.put("psiphonRegion", "SE"); values.put("torRelayCount", 80); values.put("psiphonHttp", true);
        SharedPreferences.Editor editor = memory.preferences.edit();
        AndroidCoreSettings.store(editor, values); assertTrue(editor.commit());
        Map<String, Object> reloaded = CoreSettings.values(memory.preferences.getAll());
        assertEquals("SE", reloaded.get("psiphonRegion"));
        assertEquals(80, reloaded.get("torRelayCount"));
        assertEquals(true, reloaded.get("psiphonHttp"));
    }

    @Test public void psiphonTopologyChangesPreserveChainTransportAcrossSaveAndBackup() {
        Memory memory = new Memory();
        memory.saved.put("psiphonTransport", "direct");
        memory.saved.put("psiphonRegion", "DE");
        memory.saved.put("psiphonHttp", true);
        AndroidCoreSettings.migrate(memory.preferences);
        for (String topology : new String[]{"chain", "only", "off", "chain"}) {
            Map<String, Object> values = CoreSettings.values(memory.preferences.getAll());
            values.put("psiphonMode", topology);
            SharedPreferences.Editor editor = memory.preferences.edit();
            AndroidCoreSettings.store(editor, values);
            assertTrue(editor.commit());
            AndroidCoreSettings.migrate(memory.preferences);
            Map<String, Object> reloaded = CoreSettings.values(memory.preferences.getAll());
            Map<String, Object> restored = CoreSettingsBackup.restoreValues(new LinkedHashMap<>(), CoreSettingsBackup.exportValues(reloaded));
            assertEquals(topology, restored.get("psiphonMode"));
            assertEquals("direct", restored.get("psiphonTransport"));
            assertEquals("DE", restored.get("psiphonRegion"));
            assertEquals(true, restored.get("psiphonHttp"));
            Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(restored, "wg");
            assertEquals(topology.equals("chain") ? "direct" : null, environment.get("AETHER_PSIPHON_MODE"));
        }
    }

    @Test public void wrongTypeAndFailedMigrationCannotSilentlyCommit() {
        Memory memory = new Memory();
        Map<String, Object> invalid = new LinkedHashMap<>(); invalid.put("psiphonHttp", "true");
        assertThrows(IllegalArgumentException.class, () -> AndroidCoreSettings.store(memory.editor(), invalid));
        assertTrue(memory.saved.isEmpty());
        memory.commitSucceeds = false;
        assertThrows(IllegalStateException.class, () -> AndroidCoreSettings.migrate(memory.preferences));
        assertTrue(memory.saved.isEmpty());
    }

    @Test public void futureSchemaIsRejectedAndResetUsesSafeDefaults() {
        Memory memory = new Memory(); memory.saved.put("coreSettingsSchema", 9);
        assertThrows(IllegalArgumentException.class, () -> AndroidCoreSettings.migrate(memory.preferences));
        assertEquals(9, memory.saved.get("coreSettingsSchema"));
        memory.saved.clear(); AndroidCoreSettings.migrate(memory.preferences);
        assertEquals("off", memory.saved.get("psiphonMode"));
        assertEquals(false, memory.saved.get("exitLocationEnabled"));
    }

    @Test public void unavailablePrivacyRecoveryPersistsWithoutResettingUserConfiguration() {
        Memory memory = new Memory();
        memory.saved.put("scan", 4);
        memory.saved.put("mode", "manual");
        memory.saved.put("torProxy", true);
        memory.saved.put("torHttp", true);
        memory.saved.put("psiphonRegion", "DE");
        AndroidCoreSettings.migrate(memory.preferences);
        SharedPreferences.Editor editor = memory.preferences.edit();
        AndroidCoreSettings.store(editor, PrivacySettings.disableUnavailable(memory.preferences.getAll()));
        editor.apply();
        assertEquals(4, memory.saved.get("scan"));
        assertEquals("manual", memory.saved.get("mode"));
        assertEquals(true, memory.saved.get("torProxy"));
        assertEquals("DE", memory.saved.get("psiphonRegion"));
        assertNull(PrivacySettings.unavailable(CoreSettings.values(memory.preferences.getAll())));
    }
}
