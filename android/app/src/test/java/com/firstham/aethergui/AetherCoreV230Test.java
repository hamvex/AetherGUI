package com.firstham.aethergui;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.034 dedicated Aether Core v2.3.0 regression coverage (PROMPT PHASE 36.2-36.10 + 25).
 * Every test pins the exact env/preference contract the service maps to the pinned v2.3.0
 * binary: Gool topologies, ECH modes and Auto lookup, TLS fingerprint controls, Noize
 * profiles, the exit-country policy, and the backup/restore roundtrip of every new field.
 */
public final class AetherCoreV230Test {
    private Map<String, Object> defaults() { return CoreSettings.values(new LinkedHashMap<>()); }

    // --- PHASE 36.2: the two v2.3.0 Gool topologies ------------------------------------------

    @Test public void goolOverMasqueRunsTheNewCoreTopologyWithoutClassicVariables() {
        Map<String, Object> v = defaults();
        v.put("goolInnerPeer", "162.159.192.1:2408");
        v.put("ech", "auto");
        v.put("startupSecs", 40);
        Map<String, String> env = CoreSettings.environment(v, "gool", "h2");
        assertEquals("masque", v.get("goolMode"));
        assertFalse("the new topology must not preset the classic mode variable", env.containsKey("AETHER_GOOL_MODE"));
        assertFalse("WiW endpoints select classic in the Core and must stay unmapped", env.containsKey("AETHER_WIW_OUTER_PEER"));
        assertFalse(env.containsKey("AETHER_WIW_INNER_PEER"));
        assertEquals("162.159.192.1:2408", env.get("AETHER_GOOL_INNER"));
        assertEquals("auto", env.get("AETHER_ECH"));
        assertEquals("40", env.get("AETHER_MASQUE_STARTUP_SECS"));
        assertEquals("30", env.get("AETHER_TCP_CONNECT_SECS"));
        assertEquals("5", env.get("AETHER_WG_KEEPALIVE"));
    }

    @Test public void classicGoolSelectsTheLegacyTopologyExplicitlyAndNeverSendsTheInnerPeer() {
        Map<String, Object> v = defaults();
        v.put("goolMode", "classic");
        v.put("goolInnerPeer", "162.159.192.1:2408");
        v.put("wiwOuterPeer", "162.159.192.2:2408");
        v.put("wiwInnerPeer", "188.114.96.1:2408");
        Map<String, String> env = CoreSettings.environment(v, "gool", "h2");
        assertEquals("classic", env.get("AETHER_GOOL_MODE"));
        assertEquals("162.159.192.2:2408", env.get("AETHER_WIW_OUTER_PEER"));
        assertEquals("188.114.96.1:2408", env.get("AETHER_WIW_INNER_PEER"));
        assertFalse(env.containsKey("AETHER_GOOL_INNER"));
        assertFalse("no malformed empty env name may leak the withheld value", env.containsKey(""));
        assertFalse(env.containsValue("162.159.192.1:2408"));
    }

    @Test public void goolModeAcceptsExactlyTheTwoV230Topologies() {
        for (String invalid : new String[]{"masque-carried", "Classic", "", "wiw"}) {
            Map<String, Object> v = defaults();
            v.put("goolMode", invalid);
            assertEquals("goolMode", CoreSettings.invalid(v, "gool", "h2"));
        }
        for (String valid : new String[]{"masque", "classic"}) {
            Map<String, Object> v = defaults();
            v.put("goolMode", valid);
            assertNull(CoreSettings.invalid(v, "gool", "h2"));
        }
    }

    @Test public void strictTopologySelectionNeverMapsTheOtherModesVariables() {
        // Classic must not receive the MASQUE-carrier-only ECH selection; Gool over MASQUE
        // must not receive the classic-only WiW endpoints. A manual selection therefore
        // cannot be silently satisfied by the other topology's variables.
        Map<String, Object> classic = defaults();
        classic.put("goolMode", "classic");
        classic.put("ech", "auto");
        classic.put("h2Fragment", true);
        Map<String, String> classicEnv = CoreSettings.environment(classic, "gool", "h2");
        assertFalse(classicEnv.containsKey("AETHER_ECH"));
        assertFalse(classicEnv.containsKey("AETHER_MASQUE_H2_FRAGMENT"));
        assertFalse(classicEnv.containsKey("AETHER_QUIC_V2"));

        Map<String, Object> carried = defaults();
        carried.put("wiwOuterPeer", "162.159.192.2:2408");
        carried.put("wiwInnerPeer", "188.114.96.1:2408");
        Map<String, String> carriedEnv = CoreSettings.environment(carried, "gool", "h3");
        assertFalse(carriedEnv.containsKey("AETHER_WIW_OUTER_PEER"));
        assertFalse(carriedEnv.containsKey("AETHER_WIW_INNER_PEER"));
    }

    @Test public void bothGoolTopologiesCarryThroughPsiphonAndTorChains() {
        for (String mode : new String[]{"masque", "classic"}) {
            Map<String, Object> chain = defaults();
            chain.put("goolMode", mode);
            chain.put("psiphonMode", "chain");
            chain.put("psiphonTransport", "auto");
            chain.put("psiphonRegion", "DE");
            Map<String, String> env = CoreSettings.environment(chain, "gool", "h2");
            assertEquals("chain", env.get("AETHER_PSIPHON"));
            assertEquals("auto", env.get("AETHER_PSIPHON_MODE"));
            assertEquals("DE", env.get("AETHER_PSIPHON_REGION"));
            assertEquals("masque".equals(mode) ? null : "classic", env.get("AETHER_GOOL_MODE"));

            Map<String, Object> tor = defaults();
            tor.put("goolMode", mode);
            tor.put("torProxy", true);
            tor.put("torMode", "chain");
            Map<String, String> torEnv = CoreSettings.environment(tor, "gool", "h2");
            assertEquals("chain", torEnv.get("AETHER_TOR"));
            assertEquals(PrivacyRuntimeConfig.TOR_SOCKS, torEnv.get("AETHER_TOR_BIND"));
            assertEquals("masque".equals(mode) ? null : "classic", torEnv.get("AETHER_GOOL_MODE"));
        }
    }

    @Test public void migratedGoolUsersKeepTheLegacyTopologyWhileFreshInstallsGetTheNewDefault() {
        for (int protocol : new int[]{2, 6, 9}) {
            Memory memory = new Memory();
            memory.saved.put("protocol", protocol);
            AndroidCoreSettings.migrate(memory.preferences);
            assertEquals("an upgrading dev.020 Gool user keeps the legacy topology", "classic", memory.saved.get("goolMode"));
        }
        Memory fresh = new Memory();
        fresh.saved.put("protocol", 1);
        AndroidCoreSettings.migrate(fresh.preferences);
        assertFalse("a non-Gool user has no stored goolMode; the new default comes from CoreSettings",
                fresh.saved.containsKey("goolMode"));
        assertEquals("masque", CoreSettings.values(fresh.preferences.getAll()).get("goolMode"));
        // An explicit stored choice is never rewritten.
        Memory chosen = new Memory();
        chosen.saved.put("protocol", 2);
        chosen.saved.put("goolMode", "masque");
        AndroidCoreSettings.migrate(chosen.preferences);
        assertEquals("masque", chosen.saved.get("goolMode"));
    }

    // --- PHASE 36.3: ECH modes, Custom config and the Auto lookup fields ---------------------

    @Test public void echModesAreOffAutoAndCustomWithExactRuntimeMapping() {
        assertFalse(CoreSettings.environment(defaults(), "masque", "h2").containsKey("AETHER_ECH"));

        Map<String, Object> v = defaults();
        v.put("ech", "auto");
        assertEquals("auto", CoreSettings.environment(v, "mim", "h2").get("AETHER_ECH"));
        assertEquals("auto", CoreSettings.environment(v, "gool", "h2").get("AETHER_ECH"));
        // Classic gool and plain WireGuard do not start an EchSession in v2.3.0.
        v.put("goolMode", "classic");
        assertFalse(CoreSettings.environment(v, "gool", "h2").containsKey("AETHER_ECH"));
        v.put("goolMode", "masque");
        assertFalse(CoreSettings.environment(v, "wg", "h2").containsKey("AETHER_ECH"));
    }

    @Test public void customEchMustBeAWellFormedConfigListBeforeCoreStart() {
        byte[] config = new byte[8];
        config[0] = 0; config[1] = 6; // ECHConfigList length covers the remaining six bytes.
        String valid = Base64.getEncoder().encodeToString(config);
        Map<String, Object> v = defaults();
        v.put("ech", valid);
        assertEquals(valid, CoreSettings.environment(v, "masque", "h2").get("AETHER_ECH"));
        for (String malformed : new String[]{"not base64!!", "AAAA", "AAA="}) {
            v.put("ech", malformed);
            assertEquals("ech", CoreSettings.invalid(v, "masque", "h2"));
        }
    }

    @Test public void echAutoLookupFieldsFollowTheExactUpstreamUrlContract() {
        // The exact forms upstream's own dns.rs tests accept, mirrored one for one.
        for (String url : new String[]{"udp://1.1.1.1", " UDP://8.8.8.8:5353", "tcp://1.1.1.1",
                "tcp://[2606:4700:4700::1111]", "udp://[::1]:5353/", "udp://2606:4700:4700::1111",
                "https://doq.dns4all.eu/dns-query", "https://1.1.1.1:8443/dns-query", "https://dns.example",
                "https://dns.example/dns-query@address=1.2.3.4", "https://dns.example/dns-query@sni=resolver.example",
                "https://dns.example/dns-query@address=[2606:4700::1]@sni=a.example",
                "https://dns.example/dns-query@sni=a.example@address=1.2.3.4"}) {
            Map<String, Object> v = defaults();
            v.put("ech", "auto");
            v.put("echDns", url);
            assertNull(url, CoreSettings.invalid(v, "masque", "h2"));
        }
        for (String url : new String[]{"ftp://1.1.1.1", "udp://resolver.example", "udp://1.1.1.1?x=1",
                "udp://", "udp://1.1.1.1@wrong=a", "https://dns.example/dns-query@address=",
                "https://dns.example/dns-query@address=1.2.3.4@address=5.6.7.8", "https://dns.example@",
                "resolver.example", "udp://1.1.1.1:99999", "tcp://[2606:4700::1", "https:///dns-query"}) {
            Map<String, Object> v = defaults();
            v.put("echDns", url);
            assertEquals("echDns", CoreSettings.invalid(v, "masque", "h2"));
        }
        Map<String, Object> v = defaults();
        v.put("ech", "auto");
        v.put("echDns", "udp://1.1.1.1");
        v.put("echDomain", "cloudflare-ech.com");
        Map<String, String> env = CoreSettings.environment(v, "masque", "h2");
        assertEquals("udp://1.1.1.1", env.get("AETHER_ECH_DNS"));
        assertEquals("cloudflare-ech.com", env.get("AETHER_ECH_DOMAIN"));
        // The lookup fields are inert without a carrier (classic gool / plain WireGuard).
        v.put("goolMode", "classic");
        Map<String, String> classic = CoreSettings.environment(v, "gool", "h2");
        assertFalse(classic.containsKey("AETHER_ECH_DNS"));
        assertFalse(classic.containsKey("AETHER_ECH_DOMAIN"));
    }

    @Test public void echDomainIsAPlainDnsName() {
        Map<String, Object> v = defaults();
        v.put("echDomain", "https://cloudflare-ech.com");
        assertEquals("echDomain", CoreSettings.invalid(v, "masque", "h2"));
        v.put("echDomain", "cloud flare");
        assertEquals("echDomain", CoreSettings.invalid(v, "masque", "h2"));
        v.put("echDomain", "ech.example.");
        assertNull(CoreSettings.invalid(v, "masque", "h2"));
        // upstream valid_domain(): single-label names are domains too.
        v.put("echDomain", "localhost");
        assertNull(CoreSettings.invalid(v, "masque", "h2"));
    }

    // --- PHASE 36.4: TLS fingerprint controls -------------------------------------------------

    @Test public void tlsDefaultsSendNothingAndGreaseStaysOnByDefault() {
        for (String protocol : new String[]{"masque", "wg", "gool", "mim"}) {
            Map<String, String> env = CoreSettings.environment(defaults(), protocol, "h2");
            assertFalse(env.containsKey("AETHER_TLS_CIPHERS"));
            assertFalse(env.containsKey("AETHER_TLS_GROUPS"));
            assertFalse("GREASE is on by default (upstream), so the disable flag is absent", env.containsKey("AETHER_DISABLE_GREASE"));
            assertFalse(env.containsKey("AETHER_TLS_VERIFY"));
        }
    }

    @Test public void customTlsCiphersAndGroupsAreMappedVerbatimForEveryProtocol() {
        Map<String, Object> v = defaults();
        v.put("tlsCiphers", "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES128-GCM-SHA256");
        v.put("tlsGroups", "X25519:P-256:P-384");
        v.put("tlsVerify", true);
        for (String protocol : new String[]{"masque", "wg", "gool", "mim"}) {
            Map<String, String> env = CoreSettings.environment(v, protocol, "h2");
            assertEquals("ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES128-GCM-SHA256", env.get("AETHER_TLS_CIPHERS"));
            assertEquals("X25519:P-256:P-384", env.get("AETHER_TLS_GROUPS"));
            assertEquals("1", env.get("AETHER_TLS_VERIFY"));
        }
    }

    @Test public void malformedTlsSyntaxIsRejectedBeforeCoreStart() {
        for (String bad : new String[]{"ECDHE RSA AES", "AES128-GCM-SHA256;BAD", "X25519/P-256", "cipher cipher"}) {
            Map<String, Object> v = defaults();
            v.put("tlsCiphers", bad);
            assertEquals("tlsCiphers", CoreSettings.invalid(v, "masque", "h2"));
            v.remove("tlsCiphers");
            v.put("tlsGroups", bad);
            assertEquals("tlsGroups", CoreSettings.invalid(v, "masque", "h2"));
        }
    }

    @Test public void greaseMappingFollowsUpstreamSemanticsUninverted() {
        Map<String, Object> v = defaults();
        v.put("grease", false);
        assertEquals("1", CoreSettings.environment(v, "masque", "h2").get("AETHER_DISABLE_GREASE"));
        v.put("grease", true);
        assertFalse(CoreSettings.environment(v, "masque", "h2").containsKey("AETHER_DISABLE_GREASE"));
    }

    @Test public void tlsVerifyIsOptInPinCheckingMappedToTheExactCoreVariable() {
        Map<String, Object> v = defaults();
        v.put("tlsVerify", true);
        assertEquals("1", CoreSettings.environment(v, "masque", "h3").get("AETHER_TLS_VERIFY"));
        v.put("tlsVerify", false);
        assertFalse(CoreSettings.environment(v, "masque", "h3").containsKey("AETHER_TLS_VERIFY"));
    }

    // --- PHASE 36.5: the MASQUE carrier contract of Gool over MASQUE + fragmentation -----------

    @Test public void goolOverMasqueHonorsTheCarrierSelectionAndFragmentationContract() {
        Map<String, Object> v = defaults();
        v.put("h2Fragment", true);
        v.put("h2FragmentSize", "100-200");
        v.put("h2FragmentDelay", "3-7");
        Map<String, String> h2 = CoreSettings.environment(v, "gool", "h2");
        assertEquals("1", h2.get("AETHER_MASQUE_H2_FRAGMENT"));
        assertEquals("100-200", h2.get("AETHER_MASQUE_H2_FRAGMENT_SIZE"));
        assertEquals("3-7", h2.get("AETHER_MASQUE_H2_FRAGMENT_DELAY"));
        Map<String, String> h3 = CoreSettings.environment(v, "gool", "h3");
        assertFalse("fragmentation is H2-only in v2.3.0", h3.containsKey("AETHER_MASQUE_H2_FRAGMENT"));
        assertFalse(h3.containsKey("AETHER_MASQUE_H2_FRAGMENT_SIZE"));
        // The classic topology is not a MASQUE carrier.
        v.put("goolMode", "classic");
        assertFalse(CoreSettings.environment(v, "gool", "h2").containsKey("AETHER_MASQUE_H2_FRAGMENT"));
    }

    @Test public void quicV2AppliesToTheGoolCarrierLikeMasque() {
        Map<String, Object> v = defaults();
        v.put("quicV2", false);
        assertEquals("0", CoreSettings.environment(v, "gool", "h3").get("AETHER_QUIC_V2"));
        v.put("quicV2", true);
        assertEquals("1", CoreSettings.environment(v, "gool", "h3").get("AETHER_QUIC_V2"));
        v.put("goolMode", "classic");
        assertFalse(CoreSettings.environment(v, "gool", "h3").containsKey("AETHER_QUIC_V2"));
    }

    @Test public void fragmentSizeDefaultFollowsTheV230UpstreamDefaultAndMigratesOnce() {
        assertEquals("8-16", CoreSettings.DEFAULTS.get("h2FragmentSize"));
        Memory old = new Memory();
        old.saved.put("h2FragmentSize", "16-32");
        AndroidCoreSettings.migrate(old.preferences);
        assertEquals("the dev.020-era default moves to the v2.3.0 upstream default", "8-16", old.saved.get("h2FragmentSize"));
        assertEquals(true, old.saved.get("fragmentSizeV230"));
        // An explicit user choice is never touched.
        Memory explicit = new Memory();
        explicit.saved.put("h2FragmentSize", "100-200");
        AndroidCoreSettings.migrate(explicit.preferences);
        assertEquals("100-200", explicit.saved.get("h2FragmentSize"));
        // The conversion is one-time: a later explicit 16-32 selection stays.
        old.saved.put("h2FragmentSize", "16-32");
        AndroidCoreSettings.migrate(old.preferences);
        assertEquals("16-32", old.saved.get("h2FragmentSize"));
    }

    @Test public void fragmentSniSplitIsMappedOnlyWhileFragmentationIsEnabled() {
        Map<String, Object> v = defaults();
        v.put("h2FragmentSni", true);
        assertFalse(CoreSettings.environment(v, "masque", "h2").containsKey("AETHER_MASQUE_H2_FRAGMENT_SNI"));
        v.put("h2Fragment", true);
        assertEquals("1", CoreSettings.environment(v, "masque", "h2").get("AETHER_MASQUE_H2_FRAGMENT_SNI"));
    }

    // --- PHASE 36.6: Noize profiles map independently -----------------------------------------

    @Test public void everyNoizeProfileMapsToItsOwnCoreValue() {
        String[] expected = {"firewall", "gfw", "balanced", "aggressive", "off", "light"};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], VpnConnectionController.obfuscationMode(index));
            assertEquals(expected[index], VpnConnectionController.obfuscationMode(index));
        }
        // v2.3.0 made firewall and gfw real profiles: neither may fall through to balanced.
        assertNotEquals(VpnConnectionController.obfuscationMode(2), VpnConnectionController.obfuscationMode(0));
        assertNotEquals(VpnConnectionController.obfuscationMode(2), VpnConnectionController.obfuscationMode(1));
        assertEquals("firewall", VpnConnectionController.obfuscationMode(0));
        assertEquals("gfw", VpnConnectionController.obfuscationMode(1));
        // Out-of-range indices keep the safe default instead of guessing a profile.
        assertEquals("balanced", VpnConnectionController.obfuscationMode(-1));
        assertEquals("balanced", VpnConnectionController.obfuscationMode(6));
    }

    // --- PHASE 36.9: the exit-country policy ---------------------------------------------------

    @Test public void exitPolicyOffEmitsNoCountryVariableSoAnyExitIncludingIrIsValid() {
        Map<String, Object> v = defaults();
        v.put("h2Fragment", true);
        Map<String, String> env = CoreSettings.environment(v, "wg", "h2");
        assertFalse("no hidden country blacklist: OFF means nothing is looked up or refused",
                env.containsKey("AETHER_EXIT_LOC"));
        assertFalse(env.containsKey("AETHER_EXIT_LOC_SECS"));
        assertEquals("", PrivacySettings.exitPolicy(v));
        assertNull(CoreSettings.invalid(v, "wg", "h2"));
        assertEquals("", PrivacySettings.exitPolicy(v));
    }

    @Test public void exitPolicyAllowAndExcludeMapToTheExactCoreSyntax() {
        Map<String, Object> v = defaults();
        v.put("exitLocationEnabled", true);
        v.put("exitLocationMode", "allow");
        v.put("exitLocationCountries", "de,se,de");
        assertEquals("DE,SE", PrivacySettings.exitPolicy(v));
        v.put("exitLocationMode", "exclude");
        v.put("exitLocationCountries", "ir,az,ir");
        assertEquals("!IR,AZ", PrivacySettings.exitPolicy(v));
        v.put("exitLocationCountries", "IR");
        assertEquals("!IR", PrivacySettings.exitPolicy(v));
        // A policy the runtime has not re-verified on v2.3.0 refuses to start rather than
        // silently enforcing or ignoring the user's list.
        RuntimeException thrown = catchRunnable(() -> CoreSettings.environment(v, "wg", "h2"));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("pending runtime validation"));
        assertEquals("exitLocationEnabled", PrivacySettings.unavailable(v));
    }

    @Test public void exitLocationRecheckFollowsTheCoreBounds() {
        for (int bad : new int[]{0, 86401, -1}) {
            Map<String, Object> v = defaults();
            v.put("exitLocationSecs", bad);
            assertEquals("exitLocationSecs", CoreSettings.invalid(v, "wg", "h2"));
        }
        Map<String, Object> v = defaults();
        v.put("exitLocationSecs", 86400);
        assertNull(CoreSettings.invalid(v, "wg", "h2"));
        assertEquals(60, CoreSettings.DEFAULTS.get("exitLocationSecs"));
    }

    // --- PHASE 36.10 + PHASE 25: every new field survives backup, reset and restore ------------

    @Test public void v230FieldsSurviveBackupResetAndRestoreExactly() {
        Map<String, Object> nonDefault = defaults();
        nonDefault.put("goolMode", "classic");
        nonDefault.put("goolInnerPeer", "162.159.192.1:2408");
        nonDefault.put("apiFragment", true);
        nonDefault.put("ech", "auto");
        nonDefault.put("echDns", "udp://1.1.1.1");
        nonDefault.put("echDomain", "cloudflare-ech.com");
        nonDefault.put("tlsCiphers", "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES128-GCM-SHA256");
        nonDefault.put("tlsGroups", "X25519:P-256");
        nonDefault.put("grease", false);
        nonDefault.put("tlsVerify", true);
        nonDefault.put("enrollAddress", "[2606:4700::1]:8443");
        nonDefault.put("reprovision", false);
        nonDefault.put("perfProfile", "low");
        nonDefault.put("statsLogging", true);
        nonDefault.put("tcpConnectSecs", 45);
        nonDefault.put("h2KeepaliveSecs", 20);
        nonDefault.put("h2KeepaliveTimeoutSecs", 25);
        nonDefault.put("wgEndpointCooldownSecs", 400);
        nonDefault.put("wgStaleSecs", 15);
        nonDefault.put("exitLocationSecs", 120);
        nonDefault.put("h2FragmentSni", true);
        nonDefault.put("h2Fragment", true);
        nonDefault.put("h2FragmentSize", "100-200");
        // Secrets and local-only values stay out of the portable backup.
        nonDefault.put("upstreamProxy", "socks5://alice:secret@127.0.0.1:1080");
        nonDefault.put("accessClientId", "client");
        nonDefault.put("accessClientSecret", "secret");

        Map<String, Object> portable = CoreSettingsBackup.exportValues(nonDefault);
        assertFalse(portable.containsKey("upstreamProxy"));
        assertFalse(portable.containsKey("accessClientId"));
        assertFalse(portable.containsKey("accessClientSecret"));

        // A reset device starts from the intended defaults, then restores the backup.
        Map<String, Object> reset = CoreSettings.values(new LinkedHashMap<>());
        Map<String, Object> restored = CoreSettingsBackup.restoreValues(reset, portable);
        for (String key : new String[]{"goolMode", "goolInnerPeer", "apiFragment", "ech", "echDns", "echDomain",
                "tlsCiphers", "tlsGroups", "grease", "tlsVerify", "enrollAddress", "reprovision", "perfProfile",
                "statsLogging", "tcpConnectSecs", "h2KeepaliveSecs", "h2KeepaliveTimeoutSecs", "wgEndpointCooldownSecs",
                "wgStaleSecs", "exitLocationSecs", "h2FragmentSni", "h2Fragment", "h2FragmentSize"})
            assertEquals("field " + key, nonDefault.get(key), restored.get(key));
        assertEquals("", restored.get("upstreamProxy"));
        assertEquals("", restored.get("accessClientId"));
        // The restored configuration is exactly what the runtime would consume. Classic is
        // not a MASQUE carrier, so ECH stays unrequested while the TLS controls apply.
        Map<String, String> env = CoreSettings.environment(restored, "gool", "h2");
        assertEquals("classic", env.get("AETHER_GOOL_MODE"));
        assertEquals("1", env.get("AETHER_TLS_VERIFY"));
        assertEquals("1", env.get("AETHER_DISABLE_GREASE"));
        assertEquals("[2606:4700::1]:8443", env.get("AETHER_ENROLL_ADDRESS"));
        assertFalse(env.containsKey("AETHER_ECH"));
        assertFalse(env.containsKey("AETHER_ECH_DNS"));
        // Switching the same restored settings to Gool over MASQUE requests ECH with the
        // exact Auto lookup values the backup carried.
        restored.put("goolMode", "masque");
        Map<String, String> carried = CoreSettings.environment(restored, "gool", "h2");
        assertEquals("auto", carried.get("AETHER_ECH"));
        assertEquals("udp://1.1.1.1", carried.get("AETHER_ECH_DNS"));
        assertEquals("cloudflare-ech.com", carried.get("AETHER_ECH_DOMAIN"));
    }

    @Test public void restoredMalformedV230ValuesFailSafelyInsteadOfCrashing() {
        Map<String, Object> portable = CoreSettingsBackup.exportValues(defaults());
        portable.put("goolMode", "broken");
        // A malformed value is rejected with the field named, before any saved mutation;
        // the import path surfaces it as a restore error instead of a crash.
        RuntimeException thrown = catchRunnable(() -> CoreSettingsBackup.restoreValues(defaults(), portable));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("goolMode"));
        portable.put("goolMode", "classic");
        portable.put("tlsGroups", "bad groups");
        assertThrows(IllegalArgumentException.class, () -> CoreSettingsBackup.restoreValues(defaults(), portable));
    }

    // --- PHASE 11.2 / 19: enrollment address and expert controls --------------------------------
    @Test public void enrollAddressAcceptsTheExactUpstreamForms() {
        for (String value : new String[]{"api.cloudflareclient.com", "1.1.1.1", "1.1.1.1:8443",
                "[2606:4700::1]", "[2606:4700::1]:8443", "api.example"}) {
            Map<String, Object> v = defaults();
            v.put("enrollAddress", value);
            assertNull(value, CoreSettings.invalid(v, "masque", "h2"));
        }
        for (String value : new String[]{"https://api.cloudflareclient.com", "1.1.1.1:99999", "[2606:4700::1", "a b"}) {
            Map<String, Object> v = defaults();
            v.put("enrollAddress", value);
            assertEquals("enrollAddress", CoreSettings.invalid(v, "masque", "h2"));
        }
    }

    @Test public void performanceAndReliabilityControlsMapWithTheirUpstreamDefaults() {
        // The WireGuard-family budgets map for wg/gool; the TCP and H2 controls map everywhere.
        Map<String, String> env = CoreSettings.environment(defaults(), "wg", "h2");
        assertEquals("30", env.get("AETHER_TCP_CONNECT_SECS"));
        assertEquals("15", env.get("AETHER_MASQUE_H2_KEEPALIVE_SECS"));
        assertEquals("20", env.get("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS"));
        assertEquals("300", env.get("AETHER_WG_ENDPOINT_COOLDOWN_SECS"));
        assertEquals("10", env.get("AETHER_WG_STALE_SECS"));
        assertFalse("stats logging is opt-in diagnostics only", env.containsKey("AETHER_STATS"));
        assertFalse("reprovision defaults on: the disable flag is absent", env.containsKey("AETHER_REPROVISION"));
        assertFalse("auto performance profile sends no override", env.containsKey("AETHER_PERF_PROFILE"));

        Map<String, Object> v = defaults();
        v.put("statsLogging", true);
        v.put("reprovision", false);
        v.put("perfProfile", "low");
        v.put("tcpConnectSecs", 45);
        Map<String, String> custom = CoreSettings.environment(v, "wg", "h3");
        assertEquals("1", custom.get("AETHER_STATS"));
        assertEquals("0", custom.get("AETHER_REPROVISION"));
        assertEquals("low", custom.get("AETHER_PERF_PROFILE"));
        assertEquals("45", custom.get("AETHER_TCP_CONNECT_SECS"));
        for (String bad : new String[]{"auto", "low;high", "High", "performance"}) {
            v.put("perfProfile", bad);
            assertEquals("perfProfile", CoreSettings.invalid(v, "wg", "h3"));
        }
    }

    /** Captures the exception a runnable throws, for message assertions. */
    private static RuntimeException catchRunnable(Runnable runnable) {
        try { runnable.run(); }
        catch (RuntimeException thrown) { return thrown; }
        throw new AssertionError("expected the runnable to throw");
    }

    // The established in-memory SharedPreferences fake (AndroidCorePersistenceTest pattern).
    private static final class Memory {
        final Map<String, Object> saved = new LinkedHashMap<>();
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
                        if (method.getName().equals("commit")) { saved.putAll(pending); return true; }
                        if (method.getName().equals("apply")) { saved.putAll(pending); return null; }
                        return proxy;
                    });
        }
    }
}
