package com.firstham.aethergui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.content.res.Configuration;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.firstham.aethergui.databinding.ActivityMainBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 41;
    private static final int NOTIFICATION_REQUEST = 42;
    private static final String INTERNAL_PERMISSION = "io.github.hamvex.aethergui.permission.INTERNAL";
    private ActivityMainBinding binding;
    private SharedPreferences preferences;
    private ExecutorService diagnosticExecutor;
    private String mode = "vpn";
    private String state = "disconnected";
    private boolean receiverRegistered;
    private final StringBuilder visibleLogs = new StringBuilder();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AetherVpnService.ACTION_STATUS.equals(action)) {
                renderState(intent.getStringExtra("state"), intent.getStringExtra("message"), intent.getStringExtra("endpoint"));
            } else if (AetherVpnService.ACTION_LOG.equals(action)) {
                String lines = intent.getStringExtra("lines");
                appendLog(lines == null ? intent.getStringExtra("line") : lines);
            } else if (AetherVpnService.ACTION_STATS.equals(action)) {
                long tx = intent.getLongExtra("tx", 0);
                long rx = intent.getLongExtra("rx", 0);
                binding.trafficStat.setText(formatBytes(tx + rx));
            } else if (UpdateConfig.ACTION_STATE.equals(action)) {
                renderUpdateState();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode(themeMode(preferences.getInt("theme", 0)));
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        diagnosticExecutor = Executors.newSingleThreadExecutor();
        binding.logText.setMovementMethod(new ScrollingMovementMethod());

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), bars.top, view.getPaddingRight(), bars.bottom);
            return insets;
        });
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.surface));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.background));
        WindowCompat.getInsetsController(getWindow(), binding.root).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), binding.root).setAppearanceLightNavigationBars(!dark);

        setupDropdowns();
        restoreSettings();
        setupActions();
        requestNotificationPermission();
        AppUpdateManager.initialize(this);
        binding.statusVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        binding.currentVersionValue.setText(BuildConfig.VERSION_NAME);
        binding.autoDownloadSwitch.setChecked(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
        renderUpdateState();
        renderState(preferences.getString("state", "disconnected"), preferences.getString("message", getString(R.string.status_ready_message)), preferences.getString("endpoint", ""));
        String logs = getSharedPreferences("service_state", MODE_PRIVATE).getString("logs", "");
        if (logs != null && !logs.isEmpty()) {
            visibleLogs.append(logs);
            binding.logText.setText(logs);
        }
        if (getIntent().getBooleanExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE, false)) {
            getIntent().removeExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE);
            binding.root.post(this::connect);
        }
    }

    private void setupDropdowns() {
        setAdapter(binding.protocolInput, R.array.protocol_labels);
        setAdapter(binding.scanInput, R.array.scan_labels);
        setAdapter(binding.transportInput, R.array.transport_labels);
        setAdapter(binding.ipInput, R.array.ip_labels);
        setAdapter(binding.obfuscationInput, R.array.obfuscation_labels);
        setAdapter(binding.logInput, R.array.log_labels);
        setAdapter(binding.themeInput, R.array.theme_labels);
        setAdapter(binding.routingInput, R.array.routing_labels);
        binding.protocolInput.setOnItemClickListener((parent, view, position, id) -> updateProtocolVisibility());
        binding.themeInput.setOnItemClickListener((parent, view, position, id) -> { saveSettings(); applyTheme(position); });
        binding.routingInput.setOnItemClickListener((parent, view, position, id) -> { updateSplitUi(); saveSettings(); announceReconnect(); });
        updateProtocolVisibility();
    }

    private void setAdapter(MaterialAutoCompleteTextView view, int arrayId) {
        view.setAdapter(new ArrayAdapter<>(this, com.google.android.material.R.layout.mtrl_auto_complete_simple_item, getResources().getStringArray(arrayId)));
    }

    private void restoreSettings() {
        mode = preferences.getString("mode", "vpn");
        binding.modeGroup.check("manual".equals(mode) ? R.id.proxy_mode_button : "smart".equals(mode) ? R.id.smart_mode_button : R.id.vpn_mode_button);
        setSelection(binding.protocolInput, R.array.protocol_labels, "protocol", ConnectionDefaults.PROTOCOL_INDEX);
        setSelection(binding.scanInput, R.array.scan_labels, "scan", ConnectionDefaults.SCAN_INDEX);
        setSelection(binding.transportInput, R.array.transport_labels, "transport", 0);
        setSelection(binding.ipInput, R.array.ip_labels, "ip", 0);
        setSelection(binding.obfuscationInput, R.array.obfuscation_labels, "obfuscation", 0);
        setSelection(binding.logInput, R.array.log_labels, "log", 0);
        setSelection(binding.themeInput, R.array.theme_labels, "theme", 0);
        setSelection(binding.routingInput, R.array.routing_labels, "routing", 0);
        binding.socksInput.setText(preferences.getString("socks", "127.0.0.1:1819"));
        binding.peerInput.setText(preferences.getString("peer", ""));
        binding.mtuInput.setText(preferences.getString("mtu", "1500"));
        binding.splitAppsInput.setText(preferences.getString("splitApps", ""));
        binding.dnsSwitch.setChecked(preferences.getBoolean("dnsLeak", true));
        binding.killswitchSwitch.setChecked(preferences.getBoolean("killSwitch", false));
        binding.reconnectSwitch.setChecked(preferences.getBoolean("quickReconnect", true));
        updateModeUi();
        updateSplitUi();
    }

    private void setSelection(MaterialAutoCompleteTextView view, int arrayId, String key, int fallback) {
        String[] values = getResources().getStringArray(arrayId);
        int index = Math.max(0, Math.min(values.length - 1, preferences.getInt(key, fallback)));
        view.setText(values[index], false);
    }

    private void setupActions() {
        binding.toolbar.setOnMenuItemClickListener(this::onToolbarItem);
        binding.connectButton.setOnClickListener(v -> {
            if (isActive()) disconnect(); else connect();
        });
        binding.modeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            mode = checkedId == R.id.proxy_mode_button ? "manual" : checkedId == R.id.smart_mode_button ? "smart" : "vpn";
            updateModeUi();
            saveSettings();
            announceReconnect();
        });
        binding.advancedToggle.setOnClickListener(v -> {
            boolean show = binding.advancedContainer.getVisibility() != View.VISIBLE;
            binding.advancedContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.advancedToggle.setText(show ? R.string.hide_advanced : R.string.show_advanced);
        });
        binding.resetButton.setOnClickListener(v -> resetDefaults());
        binding.checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        binding.downloadUpdateButton.setOnClickListener(v -> {
            String status = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", "");
            if ("ready_install".equals(status)) sendBroadcast(new Intent(this, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_INSTALL));
            else {
                Toast.makeText(this, AppUpdateManager.startDownload(this, false) ? R.string.update_download_started : R.string.update_download_failed, Toast.LENGTH_SHORT).show();
            }
        });
        binding.autoDownloadSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, checked).apply();
            if (checked) AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() {
                @Override public void onComplete() { renderUpdateState(); }
                @Override public void onError(Throwable error) { renderUpdateState(); }
            });
        });
        binding.chooseAppsButton.setOnClickListener(v -> showAppPicker());
        binding.splitAppsInput.setOnClickListener(v -> showAppPicker());
        binding.testButton.setOnClickListener(v -> selfTest());
        binding.clearLogsButton.setOnClickListener(v -> {
            visibleLogs.setLength(0);
            binding.logText.setText(R.string.logs_waiting);
            getSharedPreferences("service_state", MODE_PRIVATE).edit().remove("logs").apply();
            if (isActive()) startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_CLEAR_LOGS));
        });
        View.OnClickListener telegram = v -> openTelegram();
        binding.telegramCard.setOnClickListener(telegram);
        binding.telegramButton.setOnClickListener(telegram);
    }

    private boolean onToolbarItem(MenuItem item) {
        if (item.getItemId() == R.id.action_telegram) {
            openTelegram();
            return true;
        }
        return false;
    }

    private void connect() {
        String socks = binding.socksInput.getText() == null ? "" : binding.socksInput.getText().toString().trim();
        if (!validSocksAddress(socks)) {
            binding.socksInput.setError(getString(R.string.invalid_socks));
            return;
        }
        binding.socksInput.setError(null);
        if (selectedIndex(binding.routingInput) == 2 && selectedPackages().isEmpty()) {
            Toast.makeText(this, R.string.split_include_empty, Toast.LENGTH_LONG).show();
            return;
        }
        saveSettings();
        if (!"manual".equals(mode)) {
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                startActivityForResult(permission, VPN_REQUEST);
                return;
            }
        }
        startTunnelService();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startTunnelService();
            else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_REQUEST);
        }
    }

    private void startTunnelService() {
        VpnConnectionController.connect(this, preferences);
    }

    private void disconnect() {
        renderState("disconnecting", getString(R.string.service_disconnecting), "");
        VpnConnectionController.disconnect(this);
    }

    private void selfTest() {
        final String value = text(binding.socksInput);
        if (!validSocksAddress(value)) {
            Toast.makeText(this, R.string.invalid_socks, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.testButton.setEnabled(false);
        diagnosticExecutor.execute(() -> {
            try {
                String[] parts = splitSocks(value);
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                HttpURLConnection connection = (HttpURLConnection) new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                StringBuilder result = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) result.append(line).append('\n');
                }
                runOnUiThread(() -> {
                    binding.testButton.setEnabled(true);
                    appendLog("Self-test passed");
                    new MaterialAlertDialogBuilder(this).setTitle(R.string.test_passed).setMessage(result.toString().trim()).setPositiveButton(android.R.string.ok, null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    binding.testButton.setEnabled(true);
                    appendLog("Self-test failed: " + error.getMessage());
                    Toast.makeText(this, R.string.test_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderState(String newState, String message, String endpoint) {
        state = newState == null ? "disconnected" : newState;
        String title;
        String phase;
        boolean active = isActive();
        if ("connected".equals(state)) { title = getString(R.string.status_protected); phase = getString(R.string.phase_connected); }
        else if ("starting".equals(state)) { title = getString(R.string.status_starting); phase = getString(R.string.phase_starting); }
        else if ("smart-testing".equals(state)) { title = getString(R.string.smart_mode); phase = getString(R.string.phase_testing); }
        else if ("scanning".equals(state)) { title = getString(R.string.status_scanning); phase = getString(R.string.phase_scanning); }
        else if ("securing".equals(state)) { title = getString(R.string.status_securing); phase = getString(R.string.phase_securing); }
        else if ("reconnecting".equals(state)) { title = getString(R.string.status_reconnecting); phase = getString(R.string.phase_reconnecting); }
        else if ("disconnecting".equals(state)) { title = getString(R.string.status_disconnecting); phase = getString(R.string.phase_closing); }
        else if ("blocked".equals(state)) { title = getString(R.string.status_blocked); phase = getString(R.string.phase_blocked); }
        else if ("error".equals(state)) { title = getString(R.string.status_error); phase = getString(R.string.phase_error); }
        else { title = getString(R.string.status_disconnected); phase = getString(R.string.phase_ready); }
        binding.statusTitle.setText(title);
        binding.statusPhase.setText(phase);
        binding.statusMessage.setText(message == null ? "" : message);
        binding.connectButton.setText(active ? R.string.disconnect : R.string.connect);
        binding.connectButton.setIconResource(active ? android.R.drawable.ic_media_pause : R.drawable.ic_power);
        binding.progress.setVisibility(active && !"connected".equals(state) ? View.VISIBLE : View.GONE);
        binding.statusDot.setColorFilter("connected".equals(state) ? ContextCompat.getColor(this, R.color.green) : "error".equals(state) || "blocked".equals(state) ? ContextCompat.getColor(this, R.color.red) : Color.WHITE);
        if (endpoint != null && !endpoint.isEmpty()) binding.protocolStat.setText(endpoint);
        preferences.edit().putString("state", state).putString("message", message == null ? "" : message).putString("endpoint", endpoint == null ? "" : endpoint).apply();
    }

    private void appendLog(String line) {
        if (line == null || line.isEmpty()) return;
        boolean follow = isLogAtBottom();
        if (visibleLogs.length() > 0) visibleLogs.append('\n');
        visibleLogs.append(line);
        if (visibleLogs.length() > 24_000) {
            int cut = visibleLogs.length() - 24_000;
            int newline = visibleLogs.indexOf("\n", cut);
            visibleLogs.delete(0, newline >= 0 ? newline + 1 : cut);
        }
        binding.logText.setText(visibleLogs);
        if (follow) binding.logText.post(this::scrollLogsToBottom);
    }

    private boolean isLogAtBottom() {
        android.text.Layout layout = binding.logText.getLayout();
        if (layout == null) return true;
        return layout.getHeight() - (binding.logText.getScrollY() + binding.logText.getHeight() - binding.logText.getCompoundPaddingBottom()) < 48;
    }

    private void scrollLogsToBottom() {
        android.text.Layout layout = binding.logText.getLayout();
        if (layout == null) return;
        binding.logText.scrollTo(0, Math.max(0, layout.getHeight() - binding.logText.getHeight() + binding.logText.getCompoundPaddingBottom()));
    }

    private void resetDefaults() {
        binding.protocolInput.setText(getResources().getStringArray(R.array.protocol_labels)[ConnectionDefaults.PROTOCOL_INDEX], false);
        binding.scanInput.setText(getResources().getStringArray(R.array.scan_labels)[ConnectionDefaults.SCAN_INDEX], false);
        binding.transportInput.setText(getResources().getStringArray(R.array.transport_labels)[0], false);
        binding.ipInput.setText(getResources().getStringArray(R.array.ip_labels)[0], false);
        binding.obfuscationInput.setText(getResources().getStringArray(R.array.obfuscation_labels)[0], false);
        binding.logInput.setText(getResources().getStringArray(R.array.log_labels)[0], false);
        binding.themeInput.setText(getResources().getStringArray(R.array.theme_labels)[0], false);
        binding.routingInput.setText(getResources().getStringArray(R.array.routing_labels)[0], false);
        binding.socksInput.setText(R.string.default_socks_address);
        binding.peerInput.setText("");
        binding.mtuInput.setText(R.string.default_mtu);
        binding.splitAppsInput.setText("");
        binding.dnsSwitch.setChecked(true);
        binding.killswitchSwitch.setChecked(false);
        binding.reconnectSwitch.setChecked(true);
        saveSettings();
        applyTheme(0);
        updateProtocolVisibility();
        updateSplitUi();
    }

    private void checkForUpdates() {
        android.content.SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE);
        updates.edit().putString("status", "checking").apply();
        renderUpdateState();
        binding.checkUpdatesButton.setEnabled(false);
        AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() {
            @Override public void onComplete() { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); }
            @Override public void onError(Throwable error) {
                updates.edit().putString("status", "failed").apply();
                binding.checkUpdatesButton.setEnabled(true);
                renderUpdateState();
                Toast.makeText(MainActivity.this, R.string.update_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renderUpdateState() {
        if (binding == null) return;
        android.content.SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE);
        String latest = updates.getString(UpdateConfig.KEY_LATEST_VERSION, "");
        String status = updates.getString("status", "");
        binding.latestVersionValue.setText(latest.isEmpty() ? getString(R.string.not_checked) : latest);
        int statusText;
        if ("up_to_date".equals(status)) statusText = R.string.update_up_to_date;
        else if ("available".equals(status)) statusText = R.string.update_available;
        else if ("downloading".equals(status)) statusText = R.string.update_downloading;
        else if ("ready_install".equals(status)) statusText = R.string.update_ready_install;
        else if ("installing".equals(status)) statusText = R.string.update_installing;
        else if ("checking".equals(status)) statusText = R.string.update_checking;
        else if ("download_failed".equals(status)) statusText = R.string.update_download_failed;
        else if ("verification_failed".equals(status)) statusText = R.string.update_verification_failed;
        else if ("failed".equals(status)) statusText = R.string.update_failed;
        else statusText = R.string.not_checked;
        binding.updateStatusValue.setText(statusText);
        String notes = updates.getString(UpdateConfig.KEY_RELEASE_NOTES, "");
        binding.releaseNotesValue.setText(notes);
        binding.releaseNotesValue.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        boolean action = "available".equals(status) || "download_failed".equals(status) || "verification_failed".equals(status) || "ready_install".equals(status);
        binding.downloadUpdateButton.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.downloadUpdateButton.setText("ready_install".equals(status) ? R.string.install_update : R.string.download_update);
    }

    private void updateProtocolVisibility() {
        boolean smart = "smart".equals(mode);
        binding.protocolLayout.setVisibility(smart ? View.GONE : View.VISIBLE);
        binding.transportLayout.setVisibility(!smart && selectedIndex(binding.protocolInput) == 0 ? View.VISIBLE : View.GONE);
        if (smart) binding.protocolStat.setText(R.string.auto_protocol);
        else binding.protocolStat.setText(selectedText(binding.protocolInput));
    }

    private void updateModeUi() {
        String value = "manual".equals(mode) ? getString(R.string.mode_socks_short) : "smart".equals(mode) ? getString(R.string.mode_smart_short) : getString(R.string.mode_vpn_short);
        binding.modeStat.setText(value);
        binding.modeSummary.setText("smart".equals(mode) ? R.string.smart_mode_summary : R.string.status_ready_message);
        updateProtocolVisibility();
    }

    private void updateSplitUi() {
        boolean split = selectedIndex(binding.routingInput) >= 2;
        binding.chooseAppsButton.setEnabled(split);
        binding.splitAppsInput.setEnabled(split);
    }

    private void showAppPicker() {
        SplitAppPicker.show(this, selectedPackages(), packages -> {
            List<String> ordered = new ArrayList<>(packages);
            Collections.sort(ordered);
            binding.splitAppsInput.setText(String.join("\n", ordered));
            saveSettings();
            announceReconnect();
        });
    }

    private Set<String> selectedPackages() {
        Set<String> result = new LinkedHashSet<>();
        String value = text(binding.splitAppsInput);
        for (String packageName : value.split("[\\r\\n,]+")) {
            packageName = packageName.trim();
            if (!packageName.isEmpty()) result.add(packageName);
        }
        return result;
    }

    private void announceReconnect() {
        if (isActive()) Toast.makeText(this, R.string.choose_apps_summary, Toast.LENGTH_SHORT).show();
    }

    private void applyTheme(int choice) {
        preferences.edit().putInt("theme", choice).apply();
        AppCompatDelegate.setDefaultNightMode(themeMode(choice));
    }

    private static int themeMode(int choice) {
        if (choice == 1) return AppCompatDelegate.MODE_NIGHT_NO;
        if (choice == 2) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private void saveSettings() {
        preferences.edit()
                .putString("mode", mode)
                .putInt("protocol", selectedIndex(binding.protocolInput))
                .putInt("scan", selectedIndex(binding.scanInput))
                .putInt("transport", selectedIndex(binding.transportInput))
                .putInt("ip", selectedIndex(binding.ipInput))
                .putInt("obfuscation", selectedIndex(binding.obfuscationInput))
                .putInt("log", selectedIndex(binding.logInput))
                .putInt("theme", selectedIndex(binding.themeInput))
                .putInt("routing", selectedIndex(binding.routingInput))
                .putString("socks", text(binding.socksInput))
                .putString("peer", text(binding.peerInput))
                .putString("mtu", text(binding.mtuInput))
                .putString("splitApps", text(binding.splitAppsInput))
                .putBoolean("dnsLeak", binding.dnsSwitch.isChecked())
                .putBoolean("killSwitch", binding.killswitchSwitch.isChecked())
                .putBoolean("quickReconnect", binding.reconnectSwitch.isChecked())
                .apply();
    }

    private void openTelegram() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.channel_url)))); }
        catch (Exception error) { Toast.makeText(this, R.string.telegram_fallback, Toast.LENGTH_SHORT).show(); }
    }

    private boolean isActive() { return VpnConnectionController.canDisconnect(state); }
    private int selectedIndex(MaterialAutoCompleteTextView view) { Object tag = view.getTag(); return tag instanceof Integer ? (Integer) tag : findIndex(view); }
    private int findIndex(MaterialAutoCompleteTextView view) { String value = selectedText(view); String[] all = view.getAdapter() == null ? new String[0] : getAdapterValues(view); for (int i = 0; i < all.length; i++) if (all[i].equals(value)) return i; return 0; }
    private String[] getAdapterValues(MaterialAutoCompleteTextView view) { String[] values = new String[view.getAdapter().getCount()]; for (int i = 0; i < values.length; i++) values[i] = String.valueOf(view.getAdapter().getItem(i)); return values; }
    private String selectedText(MaterialAutoCompleteTextView view) { return view.getText() == null ? "" : view.getText().toString(); }
    private String text(com.google.android.material.textfield.TextInputEditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean validSocksAddress(String value) { try { String[] parts = splitSocks(value); int port = Integer.parseInt(parts[1]); return !parts[0].isEmpty() && port > 0 && port <= 65535; } catch (Exception error) { return false; } }
    private String[] splitSocks(String value) { String input = value.trim(); if (input.startsWith("[")) { int end = input.indexOf(']'); if (end < 0 || end + 2 >= input.length() || input.charAt(end + 1) != ':') throw new IllegalArgumentException(); return new String[]{input.substring(1, end), input.substring(end + 2)}; } int split = input.lastIndexOf(':'); if (split <= 0) throw new IllegalArgumentException(); return new String[]{input.substring(0, split), input.substring(split + 1)}; }
    private String formatBytes(long bytes) {
        NumberFormat numbers = NumberFormat.getNumberInstance(Locale.US);
        numbers.setMaximumFractionDigits(1);
        if (bytes < 1024) return numbers.format(bytes) + " B";
        if (bytes < 1024 * 1024) return numbers.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return numbers.format(bytes / (1024.0 * 1024.0)) + " MB";
        return numbers.format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AetherVpnService.ACTION_STATUS);
            filter.addAction(AetherVpnService.ACTION_LOG);
            filter.addAction(AetherVpnService.ACTION_STATS);
            filter.addAction(UpdateConfig.ACTION_STATE);
            ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY));
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (diagnosticExecutor != null) diagnosticExecutor.shutdownNow();
        super.onDestroy();
    }
}
