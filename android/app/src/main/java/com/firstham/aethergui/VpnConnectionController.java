package com.firstham.aethergui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

final class VpnConnectionController {
    private static final String[] PROTOCOLS = {"masque", "wg", "gool"};
    private static final String[] SCANS = {"balanced", "turbo", "thorough", "stealth", "ironclad"};
    private static final String[] IP_MODES = {"v4", "v6", "both"};
    private static final String[] OBFUSCATION = {"firewall", "gfw", "balanced", "aggressive", "off"};
    private static final String[] LOG_LEVELS = {"info", "warn", "error", "debug", "trace"};
    private static final String[] ROUTING = {"bypass-local", "full", "split-include", "split-exclude"};

    static Intent startIntent(Context context, SharedPreferences preferences) {
        return new Intent(context, AetherVpnService.class)
                .setAction(AetherVpnService.ACTION_START)
                .putExtra("connectionMode", preferences.getString("mode", "vpn"))
                .putExtra("protocol", value(PROTOCOLS, preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX), ConnectionDefaults.PROTOCOL))
                .putExtra("scan", value(SCANS, preferences.getInt("scan", ConnectionDefaults.SCAN_INDEX), ConnectionDefaults.SCAN))
                .putExtra("transport", preferences.getInt("transport", 0) == 1 ? "h2" : "h3")
                .putExtra("ipMode", value(IP_MODES, preferences.getInt("ip", 0), "v4"))
                .putExtra("obfuscation", value(OBFUSCATION, preferences.getInt("obfuscation", 0), "firewall"))
                .putExtra("logLevel", value(LOG_LEVELS, preferences.getInt("log", 0), "info"))
                .putExtra("routing", value(ROUTING, preferences.getInt("routing", 0), "bypass-local"))
                .putExtra("socks", preferences.getString("socks", "127.0.0.1:1819"))
                .putExtra("peer", preferences.getString("peer", ""))
                .putExtra("mtu", parseMtu(preferences.getString("mtu", "1500")))
                .putExtra("splitApps", preferences.getString("splitApps", ""))
                .putExtra("dnsLeak", preferences.getBoolean("dnsLeak", true))
                .putExtra("killSwitch", preferences.getBoolean("killSwitch", false))
                .putExtra("quickReconnect", preferences.getBoolean("quickReconnect", true));
    }

    static void connect(Context context, SharedPreferences preferences) {
        ContextCompat.startForegroundService(context, startIntent(context, preferences));
    }

    static void disconnect(Context context) {
        context.startService(new Intent(context, AetherVpnService.class).setAction(AetherVpnService.ACTION_STOP));
    }

    static boolean canDisconnect(String state) {
        return "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state)
                || "securing".equals(state) || "connected".equals(state) || "reconnecting".equals(state)
                || "disconnecting".equals(state) || "blocked".equals(state);
    }

    private static String value(String[] values, int index, String fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    private static int parseMtu(String value) {
        try { return Math.max(1280, Math.min(9000, Integer.parseInt(value))); }
        catch (Exception ignored) { return 1500; }
    }

    private VpnConnectionController() { }
}
