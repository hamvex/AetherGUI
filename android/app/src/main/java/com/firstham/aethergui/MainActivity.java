package com.firstham.aethergui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 41;
    private static final int BLUE = Color.rgb(23, 103, 216);
    private static final int NAVY = Color.rgb(16, 42, 67);
    private static final int TEXT = Color.rgb(23, 36, 58);
    private static final int MUTED = Color.rgb(107, 119, 137);
    private SharedPreferences preferences;
    private LinearLayout content;
    private LinearLayout connectionView;
    private LinearLayout diagnosticsView;
    private TextView stateText;
    private TextView stateMessage;
    private TextView endpointText;
    private TextView logsText;
    private Button connectButton;
    private Button disconnectButton;
    private Button vpnModeButton;
    private Button manualModeButton;
    private Spinner protocol;
    private Spinner scan;
    private Spinner transport;
    private Spinner ipMode;
    private Spinner obfuscation;
    private Spinner logLevel;
    private Spinner routing;
    private EditText socksAddress;
    private EditText peer;
    private EditText mtu;
    private EditText splitApps;
    private Switch dnsLeak;
    private Switch killSwitch;
    private Switch quickReconnect;
    private String mode = "vpn";
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AetherVpnService.ACTION_STATUS.equals(intent.getAction())) {
                showStatus(intent.getStringExtra("state"), intent.getStringExtra("message"));
            } else if (AetherVpnService.ACTION_LOG.equals(intent.getAction())) {
                appendLog(intent.getStringExtra("line"));
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        buildUi();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AetherVpnService.ACTION_STATUS);
        filter.addAction(AetherVpnService.ACTION_LOG);
        ContextCompat.registerReceiver(this, receiver, filter,
                "io.github.hamvex.aethergui.permission.INTERNAL", null,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void buildUi() {
        LinearLayout root = column(this, Color.rgb(244, 247, 251));
        root.addView(header());
        LinearLayout tabs = row(this, Color.WHITE);
        Button connectionTab = button("CONNECTION", BLUE); Button diagnosticsTab = button("DIAGNOSTICS", MUTED);
        tabs.addView(connectionTab, weight(1)); tabs.addView(diagnosticsTab, weight(1));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(48)));
        content = column(this, Color.TRANSPARENT);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        connectionView = buildConnectionView(); diagnosticsView = buildDiagnosticsView();
        content.addView(connectionView); content.addView(diagnosticsView); diagnosticsView.setVisibility(View.GONE);
        connectionTab.setOnClickListener(v -> { connectionView.setVisibility(View.VISIBLE); diagnosticsView.setVisibility(View.GONE); connectionTab.setTextColor(BLUE); diagnosticsTab.setTextColor(MUTED); });
        diagnosticsTab.setOnClickListener(v -> { connectionView.setVisibility(View.GONE); diagnosticsView.setVisibility(View.VISIBLE); diagnosticsTab.setTextColor(BLUE); connectionTab.setTextColor(MUTED); });
        setContentView(root);
    }

    private View header() {
        LinearLayout bar = row(this, NAVY); bar.setPadding(dp(20), dp(18), dp(20), dp(17));
        LinearLayout labels = column(this, Color.TRANSPARENT);
        TextView overline = label("FIRSTHAM AETHERGUI", 10, Color.rgb(151, 178, 205)); overline.setTypeface(null, Typeface.BOLD);
        TextView title = label("Firstham AetherGui", 23, Color.WHITE); title.setTypeface(null, Typeface.BOLD);
        labels.addView(overline); labels.addView(title); bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = label("v1.7.0\nAether 1.5.0", 10, Color.rgb(191, 211, 231)); version.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); bar.addView(version);
        return bar;
    }

    private LinearLayout buildConnectionView() {
        LinearLayout page = column(this, Color.TRANSPARENT); page.setPadding(dp(16), dp(16), dp(16), dp(24));
        LinearLayout card = card();
        stateText = label("Disconnected", 24, TEXT); stateText.setTypeface(null, Typeface.BOLD);
        stateMessage = label("Ready to connect with recommended settings.", 13, MUTED);
        endpointText = label("Endpoint: Not selected", 11, MUTED);
        card.addView(label("AETHER CONNECTION", 10, BLUE)); card.addView(stateText, margin(0, 5, 0, 0)); card.addView(stateMessage, margin(0, 4, 0, 0)); card.addView(endpointText, margin(0, 12, 0, 0));
        LinearLayout actions = row(this, Color.TRANSPARENT); connectButton = button("Connect", Color.WHITE); connectButton.setBackground(round(BLUE, 10)); connectButton.setTextColor(Color.WHITE); disconnectButton = button("Disconnect", Color.WHITE); disconnectButton.setBackground(round(Color.rgb(213, 76, 91), 10)); disconnectButton.setTextColor(Color.WHITE); disconnectButton.setVisibility(View.GONE); actions.addView(connectButton, weight(1)); actions.addView(disconnectButton, marginParams(8, 0, 0, 0, 1)); card.addView(actions, margin(0, 16, 0, 0)); page.addView(card);

        LinearLayout modeCard = card(); modeCard.addView(label("CONNECTION MODE", 10, BLUE)); LinearLayout modes = row(this, Color.TRANSPARENT); vpnModeButton = button("VPN Mode", Color.WHITE); manualModeButton = button("Manual SOCKS5", MUTED); modes.addView(vpnModeButton, weight(1)); modes.addView(manualModeButton, weight(1)); modeCard.addView(modes, margin(0, 8, 0, 0)); page.addView(modeCard, margin(0, 12, 0, 0));
        vpnModeButton.setOnClickListener(v -> { mode = "vpn"; vpnModeButton.setTextColor(BLUE); manualModeButton.setTextColor(MUTED); }); manualModeButton.setOnClickListener(v -> { mode = "manual"; manualModeButton.setTextColor(BLUE); vpnModeButton.setTextColor(MUTED); });

        LinearLayout profile = card(); profile.addView(label("CONNECTION PROFILE", 10, BLUE)); profile.addView(label("Choose a protocol and scan profile. Advanced routing controls are below.", 12, MUTED), margin(0, 3, 0, 10));
        protocol = spinner(new String[]{"MASQUE", "WireGuard", "gool / WARP-in-WARP"}); scan = spinner(new String[]{"Balanced — recommended", "Turbo", "Thorough", "Stealth", "Ironclad — verified tunnel"}); transport = spinner(new String[]{"HTTP/3 (QUIC)", "HTTP/2 (TCP)"}); ipMode = spinner(new String[]{"IPv4 — recommended", "IPv6", "IPv4 + IPv6"}); obfuscation = spinner(new String[]{"Firewall — recommended", "GFW", "Balanced", "Aggressive", "Off"}); logLevel = spinner(new String[]{"Info", "Warning", "Error", "Debug", "Trace"}); routing = spinner(new String[]{"Bypass local networks", "All traffic", "Include selected apps", "Exclude selected apps"});
        profile.addView(field("Protocol", protocol)); profile.addView(field("Scan mode", scan)); profile.addView(field("MASQUE transport", transport));
        LinearLayout advancedTitle = row(this, Color.TRANSPARENT); TextView adv = label("ADVANCED SETTINGS", 11, TEXT); adv.setTypeface(null, Typeface.BOLD); Button reset = button("Reset", MUTED); advancedTitle.addView(adv, weight(1)); advancedTitle.addView(reset); profile.addView(advancedTitle, margin(0, 14, 0, 7));
        profile.addView(field("IP scan", ipMode)); profile.addView(field("Obfuscation", obfuscation)); profile.addView(field("Aether log level", logLevel)); profile.addView(field("Routing", routing));
        socksAddress = edit("127.0.0.1:1819", false); peer = edit("Optional custom endpoint", false); mtu = edit("1500", true); splitApps = edit("Android package names, one per line", false); profile.addView(field("SOCKS5 listen address", socksAddress)); profile.addView(field("Custom endpoint", peer)); profile.addView(field("TUN MTU", mtu)); profile.addView(field("Split-tunnel packages", splitApps));
        dnsLeak = toggle("DNS leak protection", true); killSwitch = toggle("Kill switch (fail closed)", false); quickReconnect = toggle("Quick reconnect", true); profile.addView(dnsLeak); profile.addView(killSwitch); profile.addView(quickReconnect); page.addView(profile, margin(0, 12, 0, 0));
        reset.setOnClickListener(v -> resetDefaults()); connectButton.setOnClickListener(v -> connect()); disconnectButton.setOnClickListener(v -> disconnect());
        Button telegram = button("Join Telegram channel  @hamvex", BLUE); telegram.setBackground(round(Color.rgb(234, 243, 255), 10)); telegram.setTextColor(BLUE); telegram.setOnClickListener(v -> openTelegram()); page.addView(telegram, margin(0, 14, 0, 0));
        TextView attribution = label("Independent frontend for CluvexStudio/Aether · Android VPNService + HEV tun2socks", 10, MUTED); attribution.setGravity(Gravity.CENTER); page.addView(attribution, margin(0, 14, 0, 0));
        return page;
    }

    private LinearLayout buildDiagnosticsView() {
        LinearLayout page = column(this, Color.TRANSPARENT); page.setPadding(dp(16), dp(16), dp(16), dp(24)); LinearLayout card = card(); card.addView(label("DIAGNOSTICS", 10, BLUE)); card.addView(label("Verify the local SOCKS5 proxy or inspect Aether output.", 14, MUTED), margin(0, 5, 0, 12)); Button test = button("Run connection self-test", Color.WHITE); test.setBackground(round(BLUE, 10)); test.setTextColor(Color.WHITE); card.addView(test); test.setOnClickListener(v -> selfTest()); Button clear = button("Clear logs", MUTED); card.addView(clear, margin(0, 6, 0, 0)); logsText = label("Waiting for Aether…", 11, Color.rgb(205, 218, 233)); logsText.setGravity(Gravity.TOP | Gravity.START); logsText.setPadding(dp(12), dp(12), dp(12), dp(12)); logsText.setBackground(round(NAVY, 10)); card.addView(logsText, margin(0, 14, 0, 0)); clear.setOnClickListener(v -> logsText.setText("")); page.addView(card); Button telegram = button("Join Telegram channel  @hamvex", BLUE); telegram.setOnClickListener(v -> openTelegram()); page.addView(telegram, margin(0, 12, 0, 0)); return page;
    }

    private void connect() {
        if ("vpn".equals(mode)) {
            Intent permission = VpnService.prepare(this);
            if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return; }
        }
        startTunnelService();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startTunnelService(); }

    private void startTunnelService() {
        Intent intent = new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_START);
        intent.putExtra("connectionMode", mode).putExtra("protocol", protocol.getSelectedItemPosition() == 1 ? "wg" : protocol.getSelectedItemPosition() == 2 ? "gool" : "masque");
        intent.putExtra("scan", new String[]{"balanced", "turbo", "thorough", "stealth", "ironclad"}[scan.getSelectedItemPosition()]); intent.putExtra("transport", transport.getSelectedItemPosition() == 1 ? "h2" : "h3"); intent.putExtra("ipMode", new String[]{"v4", "v6", "both"}[ipMode.getSelectedItemPosition()]); intent.putExtra("obfuscation", obfuscation.getSelectedItem().toString().toLowerCase(Locale.US).split(" ")[0]); intent.putExtra("logLevel", logLevel.getSelectedItem().toString().toLowerCase(Locale.US)); intent.putExtra("routing", new String[]{"bypass-local", "full", "split-include", "split-exclude"}[routing.getSelectedItemPosition()]); intent.putExtra("socks", socksAddress.getText().toString().trim()); intent.putExtra("peer", peer.getText().toString().trim()); intent.putExtra("mtu", parseMtu()); intent.putExtra("splitApps", splitApps.getText().toString()); intent.putExtra("dnsLeak", dnsLeak.isChecked()).putExtra("killSwitch", killSwitch.isChecked()).putExtra("quickReconnect", quickReconnect.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private int parseMtu() { try { return Math.max(1280, Math.min(9000, Integer.parseInt(mtu.getText().toString()))); } catch (Exception error) { return 1500; } }
    private void disconnect() { startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_STOP)); }

    private void selfTest() { new Thread(() -> { try { String[] parts = socksAddress.getText().toString().split(":"); Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))); HttpURLConnection connection = (HttpURLConnection) new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy); connection.setConnectTimeout(8000); connection.setReadTimeout(8000); StringBuilder result = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) { String line; while ((line = reader.readLine()) != null) result.append(line).append('\n'); } runOnUiThread(() -> { appendLog("Self-test passed"); Toast.makeText(this, result.toString(), Toast.LENGTH_LONG).show(); }); } catch (Exception error) { runOnUiThread(() -> { appendLog("Self-test failed: " + error.getMessage()); Toast.makeText(this, "Connection test failed", Toast.LENGTH_SHORT).show(); }); } }).start(); }
    private void showStatus(String state, String message) { boolean active = "connected".equals(state) || "scanning".equals(state) || "reconnecting".equals(state); stateText.setText("connected".equals(state) ? "Connected" : "error".equals(state) ? "Connection error" : "scanning".equals(state) ? "Scanning…" : "reconnecting".equals(state) ? "Reconnecting…" : "Disconnected"); stateText.setTextColor("connected".equals(state) ? Color.rgb(21, 155, 98) : "error".equals(state) ? Color.rgb(211, 70, 85) : TEXT); stateMessage.setText(message == null ? "" : message); connectButton.setVisibility(active ? View.GONE : View.VISIBLE); disconnectButton.setVisibility(active ? View.VISIBLE : View.GONE); }
    private void appendLog(String line) { if (logsText == null || line == null) return; String current = logsText.getText().toString(); if (current.startsWith("Waiting for")) current = ""; String next = (current + (current.isEmpty() ? "" : "\n") + line); if (next.length() > 14000) next = next.substring(next.length() - 14000); logsText.setText(next); }
    private void resetDefaults() { protocol.setSelection(0); scan.setSelection(0); transport.setSelection(0); ipMode.setSelection(0); obfuscation.setSelection(0); logLevel.setSelection(0); routing.setSelection(0); socksAddress.setText("127.0.0.1:1819"); peer.setText(""); mtu.setText("1500"); splitApps.setText(""); dnsLeak.setChecked(true); killSwitch.setChecked(false); quickReconnect.setChecked(true); }
    private void openTelegram() { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/hamvex"))); } catch (Exception error) { Toast.makeText(this, "Telegram channel: @hamvex", Toast.LENGTH_SHORT).show(); } }

    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String text, int color) { Button view = new Button(this); view.setText(text); view.setTextSize(12); view.setTextColor(color); view.setAllCaps(false); view.setGravity(Gravity.CENTER); view.setPadding(dp(8), 0, dp(8), 0); return view; }
    private Spinner spinner(String[] values) { Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values)); return spinner; }
    private EditText edit(String hint, boolean number) { EditText view = new EditText(this); view.setTextSize(13); view.setHint(hint); view.setSingleLine(!hint.contains("package")); view.setPadding(dp(12), 0, dp(12), 0); if (number) view.setInputType(InputType.TYPE_CLASS_NUMBER); return view; }
    private Switch toggle(String text, boolean checked) { Switch view = new Switch(this); view.setText(text); view.setTextSize(12); view.setTextColor(TEXT); view.setChecked(checked); view.setPadding(0, dp(3), 0, dp(3)); return view; }
    private View field(String title, View child) { LinearLayout box = column(this, Color.TRANSPARENT); box.addView(label(title, 11, MUTED)); box.addView(child, margin(0, 3, 0, 7)); return box; }
    private LinearLayout card() { LinearLayout view = column(this, Color.WHITE); view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackground(round(Color.WHITE, 16)); return view; }
    private LinearLayout column(Context context, int color) { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); view.setBackgroundColor(color); return view; }
    private LinearLayout row(Context context, int color) { LinearLayout view = column(context, color); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams marginParams(int l, int t, int r, int b, float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weight); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams weight(float weight) { return new LinearLayout.LayoutParams(0, -1, weight); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) { android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }

    @Override protected void onDestroy() { try { unregisterReceiver(receiver); } catch (Exception ignored) { } super.onDestroy(); }
}
