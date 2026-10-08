package com.firstham.aethergui;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AppUpdateManagerTest {
    @Test public void semanticVersionsCompareNumerically() {
        assertTrue(AppUpdateManager.compareVersions("1.2", "1.1") > 0);
        assertTrue(AppUpdateManager.compareVersions("1.12.0", "1.11.9") > 0);
        assertEquals(0, AppUpdateManager.compareVersions("v1.2", "1.2"));
        assertTrue(AppUpdateManager.compareVersions("v1.11.1", "v2.0.0") < 0);
        assertTrue(AppUpdateManager.compareVersions("v2.0.0", "v2.0.1") < 0);
        assertEquals(0, AppUpdateManager.compareVersions("v2.0.1", "2.0.1"));
        assertTrue(AppUpdateManager.compareVersions("2.2.0", "2.1.9") > 0);
        assertTrue(AppUpdateManager.compareVersions("2.2.0", "2.2.0") == 0);
        assertTrue(AppUpdateManager.compareVersions("2.1.9", "2.2.0") < 0);
        assertEquals(0, AppUpdateManager.compareVersions("2.2.0+build.5", "2.2.0+build.9"));
    }

    @Test public void checksumFileSelectsExactAsset() {
        String sums = "aaa  Aethon_1.2_android-arm64.apk\nabcdef  Aethon_1.2_android-universal.apk\n";
        assertEquals("abcdef", AppUpdateManager.checksumFromFile(sums, "Aethon_1.2_android-universal.apk"));
    }

    @Test public void updateChecksAreRateLimitedToTheCheckInterval() {
        long now = 1_700_000_000_000L;
        long sixHours = 6L * 60L * 60L * 1000L;
        assertTrue("never checked", AppUpdateManager.checkIntervalElapsed(0L, now));
        assertFalse("just checked", AppUpdateManager.checkIntervalElapsed(now, now));
        assertFalse("one hour ago", AppUpdateManager.checkIntervalElapsed(now - (60L * 60L * 1000L), now));
        assertTrue("interval reached", AppUpdateManager.checkIntervalElapsed(now - sixHours, now));
        assertTrue("interval passed", AppUpdateManager.checkIntervalElapsed(now - sixHours - 1L, now));
        // A clock that moved backwards must not lock checking out until the stored stamp is reached.
        assertTrue("future stamp", AppUpdateManager.checkIntervalElapsed(now + sixHours, now));
    }

    @Test public void updateMetadataIsOnlyFetchedOverHttps() throws Exception {
        // No network I/O here: openConnection() only resolves the stream handler.
        assertNotNull("HTTPS must be accepted", AppUpdateManager.openHttpsOnly(UpdateConfig.LATEST_API_URL));
        assertNotNull("HTTPS must be accepted", AppUpdateManager.openHttpsOnly(UpdateConfig.API_URL));
        String[] rejected = {
                "http://api.github.com/repos/hamvex/AetherGUI/releases/latest",
                "http://127.0.0.1:8080/releases/latest",
                "ftp://example.invalid/releases.json"};
        for (String url : rejected) {
            try {
                AppUpdateManager.openHttpsOnly(url);
                fail("a non-HTTPS update URL was accepted: " + url);
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("non-HTTPS"));
            }
        }
    }
}
