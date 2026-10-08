package com.firstham.aethergui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

final class VpnConnectionController {
    private static final String[] PROTOCOLS = {"masque", "wg", "gool", "smart", "mim"};
    private static final String[] SCANS = {"balanced", "turbo", "thorough", "verified", "ironclad"};
    private static final String[] IP_MODES = {"v4", "v6", "both"};
    private static final String[] OBFUSCATION = {"firewall", "gfw", "balanced", "aggressive", "off", "light"};
    private static final String[] ROUTING = {"bypass-local", "full", "split-include", "split-exclude"};
    static final int MIN_MTU = 1280;
    static final int MAX_MTU = 1500;
    static final int DEFAULT_MTU = 1360;

    static Intent startIntent(Context context, SharedPreferences preferences) {
        String savedMode = preferences.getString("mode", "vpn");
        String mode = normalizedMode(savedMode);
        int configuredProtocol = normalizedProtocolIndex(savedMode, preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX));
        // v2.1.1 stored Smart Connect as mode=smart. Convert it at the command boundary so old
        // preferences and backups continue to launch the new protocol-selector flow.
        String protocol = value(PROTOCOLS, configuredProtocol, ConnectionDefaults.PROTOCOL);
        return AndroidCoreSettings.copyToIntent(new Intent(context, AetherVpnService.class)
                .setAction(AetherVpnService.ACTION_START)
                .putExtra("requestedAtElapsed", SystemClock.elapsedRealtime())
                .putExtra("connectionMode", mode)
                .putExtra("protocol", protocol)
                .putExtra("requestedProtocol", protocol)
                .putExtra("scan", scanMode(preferences.getInt("scan", ConnectionDefaults.SCAN_INDEX)))
                .putExtra("transport", preferences.getInt("transport", ConnectionDefaults.TRANSPORT_INDEX) == 1 ? "h2" : "h3")
                .putExtra("ipMode", value(IP_MODES, preferences.getInt("ip", 0), "v4"))
                .putExtra("obfuscation", obfuscationMode(preferences.getInt("obfuscation", ConnectionDefaults.OBFUSCATION_INDEX)))
                .putExtra("routing", value(ROUTING, preferences.getInt("routing", 0), "bypass-local"))
                .putExtra("socks", ProxyMode.socksAddress(mode, preferences.getString("socks", ProxyMode.VPN_SOCKS_DEFAULT)))
                .putExtra("peer", preferences.getString("peer", ""))
                .putExtra("mtuMode", normalizedMtuMode(preferences.getString("mtuMode", ConnectionDefaults.MTU_MODE)))
                .putExtra("mtu", parseMtu(preferences.getString("mtu", Integer.toString(DEFAULT_MTU))))
                .putExtra("splitApps", preferences.getString("splitApps", ""))
                .putExtra("dnsLeak", preferences.getBoolean("dnsLeak", true))
                .putExtra("killSwitch", preferences.getBoolean("killSwitch", false))
                .putExtra("quickReconnect", preferences.getBoolean("quickReconnect", true))
                .putExtra("lanEnabled", preferences.getBoolean("lanEnabled", false))
                .putExtra("lanPort", preferences.getInt("lanPort", 18190)), preferences);
    }

    static void connect(Context context, SharedPreferences preferences) {
        ContextCompat.startForegroundService(context, startIntent(context, preferences));
    }

    static void disconnect(Context context) {
        context.startService(new Intent(context, AetherVpnService.class).setAction(AetherVpnService.ACTION_STOP)
                .putExtra("requestedAtElapsed", SystemClock.elapsedRealtime()));
    }

    static boolean canDisconnect(String state) {
        return "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state)
                || "securing".equals(state) || "connected".equals(state) || "reconnecting".equals(state)
                || "disconnecting".equals(state) || "blocked".equals(state);
    }

    static boolean shouldAutoConnect(boolean enabled, String state) {
        return enabled && !"checking".equals(state) && !canDisconnect(state);
    }

    static String normalizedMode(String mode) { return "manual".equals(mode) ? "manual" : "vpn"; }

    static int normalizedProtocolIndex(String mode, int protocolIndex) {
        return "smart".equals(mode) ? 3 : protocolIndex;
    }

    private static String value(String[] values, int index, String fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    static String scanMode(int savedIndex) { return value(SCANS, savedIndex, ConnectionDefaults.SCAN); }

    /** dev.020 CHANGE 3: the transport name for the stored index (0=HTTP/3, 1=HTTP/2). */
    static String transportName(int savedIndex) { return savedIndex == 1 ? "h2" : "h3"; }

    static String obfuscationMode(int savedIndex) { return value(OBFUSCATION, savedIndex, ConnectionDefaults.OBFUSCATION); }

    static String normalizedMtuMode(String value) { return "automatic".equalsIgnoreCase(value) ? "automatic" : "manual"; }

    static int parseMtu(String value) {
        try { return Math.max(MIN_MTU, Math.min(MAX_MTU, Integer.parseInt(value))); }
        catch (Exception ignored) { return DEFAULT_MTU; }
    }

    private VpnConnectionController() { }
}
