package com.firstham.aethergui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.034: the v2.3.0 CA-store contract. Aether Core 2.3.0 finds the Android CA stores
 * itself (psiphon.rs ca_store_env(): /apex/com.android.conscrypt/cacerts,
 * /system/etc/security/cacerts and the Termux/Linux bundle paths) and exports
 * SSL_CERT_DIR/SSL_CERT_FILE for the Psiphon helper only when neither variable is already
 * set. The old app-side manual export (and the whole trustedSystemCertificateDirectory
 * helper it tested) is REMOVED in dev.034: a pre-set variable would disable the Core's
 * native detection. These tests pin the new contract: the app never injects SSL_* itself.
 */
public class PrivacyRuntimeAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Map<String, Object> base() {
        Map<String, Object> values = new HashMap<>(CoreSettings.values(new HashMap<>()));
        values.put("psiphonMode", "chain");
        return values;
    }

    @Test public void neverInjectsSystemCertificateVariables() throws Exception {
        Map<String, String> environment = PrivacyRuntimeAssets.prepare(null, nativeDirectory(),
                temporary.getRoot(), "arm64-v8a", base());
        assertFalse(environment.containsKey("SSL_CERT_FILE"));
        assertFalse(environment.containsKey("SSL_CERT_DIR"));
    }

    @Test public void stillMapsTheVerifiedPsiphonHelperAndPrivateDirectory() throws Exception {
        File helper = new File(nativeDirectory(), "libpsiphon-tunnel-core.so");
        Map<String, String> environment = PrivacyRuntimeAssets.prepare(null, nativeDirectory(),
                temporary.getRoot(), "arm64-v8a", base());
        assertEquals(helper.getAbsolutePath(), environment.get("AETHER_PSIPHON_BIN"));
        assertEquals(new File(temporary.getRoot(), "aether-psiphon").getAbsolutePath(),
                environment.get("AETHER_PSIPHON_DIR"));
    }

    @Test public void psiphonOffMapsNoHelperVariables() throws Exception {
        Map<String, Object> values = base();
        values.put("psiphonMode", "off");
        Map<String, String> environment = PrivacyRuntimeAssets.prepare(null, nativeDirectory(),
                temporary.getRoot(), "arm64-v8a", values);
        assertFalse(environment.containsKey("AETHER_PSIPHON_BIN"));
        assertFalse(environment.containsKey("AETHER_PSIPHON_DIR"));
    }

    /** The integrity-verified helper directory the Gradle native-input check staged. */
    private static File nativeDirectory() {
        return new File(System.getProperty("aethon.nativeInputs"), "arm64-v8a");
    }
}
