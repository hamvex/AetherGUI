package com.firstham.aethergui;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.StatusBarManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Toast;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.firstham.aethergui.databinding.ActivityMainBinding;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.json.JSONObject;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 41;
    private static final int NOTIFICATION_REQUEST = 42;
    private static final int APPS_REQUEST = 43;
    private static final int EXPORT_REQUEST = 44;
    private static final int IMPORT_REQUEST = 45;
    private static final String INTERNAL_PERMISSION = "io.github.hamvex.aethergui.permission.INTERNAL";
    /** Taps closer together than this are swallowed so a burst cannot restart the tunnel. */
    private static final long CONNECT_DEBOUNCE_MS = 900L;
    /** How long the UI may show "Checking" before it gives up on the service answering. */
    private static final long STATE_RESOLVE_TIMEOUT_MS = 2_500L;
    private ActivityMainBinding binding;
    private ConfigurationSections configurationSections;
    private SharedPreferences preferences;
    private String state = "disconnected";
    private String page = "connect";
    private boolean receiverRegistered;
    private boolean autoConnectPending;
    private String endpoint = "";
    private String selectedProtocol = "";
    private boolean smartSelected;
    private long lastConnectActionAt;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateResolveTimeout = new Runnable() {
        @Override public void run() {
            if (binding == null || !"checking".equals(state)) return;
            renderState("disconnected", getString(R.string.status_ready_message));
        }
    };
    private final Runnable connectButtonRelease = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            binding.connectButton.setEnabled(!"disconnecting".equals(state) && !"checking".equals(state));
        }
    };
    private final Runnable updateProgressPoll = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            renderUpdateState();
            if ("downloading".equals(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""))) updateHandler.postDelayed(this, 1000);
        }
    };

    // --- dev.020 CHANGE 9: Home daily connected-time rendering --------------------------------

    /** Today's accumulated connected seconds, updated by each stats broadcast while connected. */
    private long dailyConnectedSeconds = -1L;
    /**
     * The once-per-second Home TIME renderer. Only runs while the Activity is visible (it is
     * armed from renderState/onStart and removed in onStop) and only counts the value that the
     * service's tracker computes - the display never runs its own accounting, so a paused
     * Activity cannot drift the number. The tick re-renders the same TextView without layout
     * changes (fixed HH:MM:SS format, tnum font feature) so there is no per-second relayout
     * jitter.
     */
    private final Runnable timeTick = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            if (!"connected".equals(state)) return;
            long base = dailyConnectedSeconds;
            long seconds = base >= 0L ? base + (SystemClock.elapsedRealtime() - lastDailySecondsAt) / 1000L : 0L;
            binding.timeValue.setText(formatConnectedTime(seconds));
            updateHandler.postDelayed(this, 1000L);
        }
    };
    /** Monotonic stamp of the stats broadcast that last updated the daily total. */
    private long lastDailySecondsAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AetherVpnService.ACTION_STATUS.equals(intent.getAction())) {
                if ("connected".equals(intent.getStringExtra("state")) && !"connected".equals(state)) {
                    long published = intent.getLongExtra("statusAtElapsed", 0L);
                    if (published > 0L) android.util.Log.i("AethonPerformance", "connected_ui_after_status="
                            + Math.max(0L, SystemClock.elapsedRealtime() - published) + "ms");
                }
                endpoint = safe(intent.getStringExtra("endpoint"));
                selectedProtocol = safe(intent.getStringExtra("selectedProtocol"));
                smartSelected = intent.getBooleanExtra("smartSelected", false);
                renderState(intent.getStringExtra("state"), intent.getStringExtra("message"));
                resolveAutoConnectAtStart();
            }
            else if (AetherVpnService.ACTION_STATS.equals(intent.getAction())) renderStats(intent);
            else if (UpdateConfig.ACTION_STATE.equals(intent.getAction())) renderUpdateState();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        migrateLegacySmartSelection();
        AndroidCoreSettings.migrate(preferences);
        establishDefaultProtocolOnFirstRun();
        String language = normalizedLanguage(preferences.getString("language", "en"));
        preferences.edit().putString("language", language).apply();
        applyLanguage(language, false);
        AppCompatDelegate.setDefaultNightMode(themeMode(preferences.getInt("theme", 0)));
        super.onCreate(savedInstanceState);
        if (!language.equals(activeResourceLanguage())) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
            recreate();
            return;
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyLayoutDirection(language);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // dev.017 CHANGE 1: the header background matches the current page/app background so the
        // header blends into the screen; the status bar keeps its own background color and the
        // light/dark icon appearance stays readable in both themes (values-night overrides the
        // header_* colors to the dark palette).
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.header_background));
        // dev.017 CHANGE 1: the status bar sits on its own scrim color so it remains visually
        // distinct from the header/body, with light icons in light mode and dark icons in dark
        // mode (windowLightStatusBar flips with the theme).
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.status_bar_scrim));
        boolean darkStatusBar = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), binding.root).setAppearanceLightStatusBars(!darkStatusBar);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setStatusBarContrastEnforced(false);
        // Keep the persistent header white while the drawer opens below it.
        binding.root.setScrimColor(Color.TRANSPARENT);
        binding.navigationView.setTopInsetScrimEnabled(false);
        int toolbarHeight = binding.toolbar.getLayoutParams().height;
        int toolbarLeft = binding.toolbar.getPaddingLeft(), toolbarRight = binding.toolbar.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            android.view.ViewGroup.LayoutParams statusParams = binding.statusBarScrim.getLayoutParams();
            statusParams.height = bars.top;
            binding.statusBarScrim.setLayoutParams(statusParams);
            binding.toolbar.setPadding(toolbarLeft + bars.left, 0, toolbarRight + bars.right, binding.toolbar.getPaddingBottom());
            android.view.ViewGroup.LayoutParams params = binding.toolbar.getLayoutParams();
            params.height = toolbarHeight;
            binding.toolbar.setLayoutParams(params);
            androidx.drawerlayout.widget.DrawerLayout.LayoutParams drawer =
                    (androidx.drawerlayout.widget.DrawerLayout.LayoutParams) binding.navigationView.getLayoutParams();
            drawer.topMargin = toolbarHeight + bars.top;
            drawer.bottomMargin = bars.bottom;
            drawer.leftMargin = bars.left; drawer.rightMargin = bars.right;
            binding.navigationView.setLayoutParams(drawer);
            binding.pageContainer.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        setupDropdowns();
        configurationSections = new ConfigurationSections(binding, savedInstanceState);
        coreSettingsUi = new CoreSettingsUi(binding, preferences, configurationSections);
        restoreSettings();
        setupNavigation();
        showPage(savedInstanceState == null ? "connect" : savedInstanceState.getString("page", "connect"));
        setupActions();
        requestNotificationPermission();
        AppUpdateManager.initialize(this);
        AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() {
            @Override public void onComplete() { renderUpdateState(); }
            @Override public void onError(Throwable error) { renderUpdateState(); }
        });
        binding.currentVersionValue.setText(BuildConfig.VERSION_NAME);
        binding.aboutVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        binding.autoDownloadSwitch.setChecked(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
        renderUpdateState();
        renderState(initialState(), getString(R.string.status_ready_message));
        autoConnectPending = savedInstanceState == null && preferences.getBoolean("autoConnectAtStart", false);
        if (getIntent().getBooleanExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE, false)) {
            autoConnectPending = false;
            getIntent().removeExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE);
            binding.root.post(this::connect);
        }
    }

    private CoreSettingsUi coreSettingsUi;
    private boolean restoringSettings;

    private void setupDropdowns() {
        setAdapter(binding.scanInput, R.array.scan_labels);
        setAdapter(binding.transportInput, R.array.transport_labels);
        setAdapter(binding.ipInput, R.array.ip_labels);
        setAdapter(binding.obfuscationInput, R.array.obfuscation_labels);
        setAdapter(binding.themeInput, R.array.theme_labels);
        setAdapter(binding.languageInput, R.array.language_labels);
        setAdapter(binding.echInput, R.array.ech_labels);
        // dev.034: the two v2.3.0 Gool topologies (storage: 0=Gool over MASQUE, 1=Classic).
        setAdapter(binding.goolModeInput, R.array.gool_mode_labels);
        setAdapter(binding.perfProfileInput, R.array.perf_profile_labels);
        // dev.017 CHANGES 5/7/13: the Protocol dropdown shows Smart Connect first, hides the
        // combined + Psiphon/+ Tor entries in Proxy mode (the internal full-device chains are
        // not meaningful there), and hides + Tor until its capability is verified. The adapter
        // maps dropdown positions to storage indices so the persisted "protocol" preference and
        // the service contract are unchanged.
        binding.protocolInput.setAdapter(new ProtocolAdapter());
        binding.protocolInput.setOnItemClickListener((p, v, position, id) -> {
            // dev.019 ISSUE 4: the storage index for the tapped row is captured FIRST, the
            // selection applied, and only then are saveSettings()/updateModeUi() allowed to
            // rebuild the adapter. dev.018 interleaved an adapter refresh between the tap and
            // the persist, so a base→base switch could land on the stale row set or be
            // swallowed entirely — the workaround was to hop through a combined entry first.
            int storage = protocolStorageIndex(position);
            binding.protocolInput.setTag(storage);
            applyCombinedProtocolSelection(storage);
            updateModeUi();
            saveSettings();
        });
        binding.scanInput.setOnItemClickListener((p, v, position, id) -> { binding.scanInput.setTag(position); saveSettings(); });
        binding.transportInput.setOnItemClickListener((p, v, position, id) -> { binding.transportInput.setTag(position); updateModeUi(); saveSettings(); });
        binding.ipInput.setOnItemClickListener((p, v, position, id) -> { binding.ipInput.setTag(position); saveSettings(); });
        binding.obfuscationInput.setOnItemClickListener((p, v, position, id) -> { binding.obfuscationInput.setTag(position); saveSettings(); });
        binding.themeInput.setOnItemClickListener((p, v, position, id) -> { binding.themeInput.setTag(position); applyTheme(position); });
        binding.languageInput.setOnItemClickListener((p, v, position, id) -> { preferences.edit().putString("language", position == 1 ? "fa" : "en").apply(); applyLanguage(position == 1 ? "fa" : "en", true); });
        binding.echInput.setOnItemClickListener((p, v, position, id) -> {
            binding.echInput.setTag(position);
            binding.echCustomLayout.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
            // dev.034: the Auto lookup knobs (resolver/domain) appear only in Auto mode.
            if (coreSettingsUi != null) coreSettingsUi.updateDependencies();
            saveSettings();
        });
        binding.goolModeInput.setOnItemClickListener((p, v, position, id) -> {
            binding.goolModeInput.setTag(position);
            updateModeUi();
            saveSettings();
        });
        binding.perfProfileInput.setOnItemClickListener((p, v, position, id) -> {
            binding.perfProfileInput.setTag(position);
            saveSettings();
        });
    }

    /** Current protocol display order: depends on the connection mode and the Tor gate. */
    private List<Integer> displayOrder() {
        return ProtocolOrder.displayIndices(ProxyMode.enabled(preferences.getString("mode", "vpn")),
                PrivacyCapabilities.isAvailable(PrivacyCapabilities.TOR_CHAIN));
    }

    /** Adapter that renders the protocol entries in display order and maps back to storage. */
    private final class ProtocolAdapter extends ArrayAdapter<String> {
        private final Filter fullListFilter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                List<String> snapshot = new ArrayList<>(labels());
                results.values = snapshot;
                results.count = snapshot.size();
                return results;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                if (results.values instanceof List<?>) {
                    for (Object value : (List<?>) results.values) add(String.valueOf(value));
                }
                notifyDataSetChanged();
            }
        };

        ProtocolAdapter() { super(MainActivity.this, R.layout.item_dropdown, new ArrayList<>()); refresh(); }

        /** Rebuilds the entry list after the connection mode or the Tor gate changed. */
        void refresh() {
            List<String> next = labels();
            clear();
            addAll(next);
            notifyDataSetChanged();
        }

        private List<String> labels() {
            String[] values = getResources().getStringArray(R.array.protocol_labels);
            List<String> out = new ArrayList<>();
            for (int index : displayOrder()) out.add(values[index]);
            return out;
        }

        @Override public Filter getFilter() { return fullListFilter; }
    }

    /** Storage index for a dropdown position under the current display order. */
    private int protocolStorageIndex(int position) {
        List<Integer> order = displayOrder();
        return position >= 0 && position < order.size() ? order.get(position) : 0;
    }

    private void setAdapter(MaterialAutoCompleteTextView view, int arrayId) {
        view.setAdapter(new DropdownAdapter(this, getResources().getStringArray(arrayId)));
    }

    private static final class DropdownAdapter extends ArrayAdapter<String> {
        private final List<String> options;
        private final Filter fullListFilter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = new ArrayList<>(options);
                results.count = options.size();
                return results;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                if (results.values instanceof List<?>) {
                    for (Object value : (List<?>) results.values) add(String.valueOf(value));
                }
                notifyDataSetChanged();
            }
        };

        DropdownAdapter(Context context, String[] values) {
            super(context, R.layout.item_dropdown, new ArrayList<>(java.util.Arrays.asList(values)));
            options = Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(values)));
        }

        @Override public Filter getFilter() { return fullListFilter; }
    }

    private void setupNavigation() {
        binding.toolbar.setNavigationContentDescription(R.string.open_navigation);
        binding.root.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawer) { binding.toolbar.setNavigationContentDescription(R.string.close_navigation); }
            @Override public void onDrawerClosed(View drawer) { binding.toolbar.setNavigationContentDescription(R.string.open_navigation); }
        });
        binding.toolbar.setNavigationOnClickListener(v -> {
            if (binding.root.isDrawerOpen(GravityCompat.START)) binding.root.closeDrawer(GravityCompat.START);
            else binding.root.openDrawer(GravityCompat.START);
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_telegram) return false;
            openTelegram();
            return true;
        });
        binding.navigationView.setNavigationItemSelectedListener(item -> { selectPage(item); binding.root.closeDrawer(GravityCompat.START); return true; });
        binding.navigationView.setCheckedItem(R.id.nav_connect);
        // dev.017 CHANGE 12: double-back-to-exit. The first Back press on Home shows a short
        // localized message; a second press within the timeout closes the Activity (the VpnService
        // keeps running - exiting the UI never disconnects the VPN). Internal pages navigate back
        // to the previous screen exactly as before, and an expired timeout resets the state.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (binding.root.isDrawerOpen(GravityCompat.START)) {
                    binding.root.closeDrawer(GravityCompat.START);
                } else if ("more_settings".equals(page)) {
                    showPage("configurations");
                } else if (!"connect".equals(page)) {
                    showPage("connect");
                } else {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastBackAt < BACK_EXIT_TIMEOUT_MS) {
                        lastBackAt = 0L;
                        finish();
                    } else {
                        lastBackAt = now;
                        Toast.makeText(MainActivity.this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    /** First Back-press timestamp for double-back-to-exit (CHANGE 12); 0 means not armed. */
    private long lastBackAt;
    private static final long BACK_EXIT_TIMEOUT_MS = 2000L;

    private void selectPage(MenuItem item) {
        int id = item.getItemId();
        showPage(id == R.id.nav_configurations ? "configurations" : id == R.id.nav_settings ? "settings" : id == R.id.nav_about ? "about" : "connect");
    }

    private void showPage(String destination) {
        page = destination;
        boolean more = "more_settings".equals(page);
        if (more) destination = "configurations";
        // dev.020 CHANGE 4: leaving More Settings collapses every accordion section so a later
        // return always starts fully collapsed (the visual state is never persisted, and
        // collapsing never touches any actual setting value).
        if (!more && configurationSections != null) configurationSections.collapseAll();
        binding.homePage.setVisibility("connect".equals(page) ? View.VISIBLE : View.GONE);
        binding.configurationsPage.setVisibility("configurations".equals(page) && !more ? View.VISIBLE : View.GONE);
        binding.moreSettingsPage.setVisibility(more ? View.VISIBLE : View.GONE);
        binding.settingsPage.setVisibility("settings".equals(page) ? View.VISIBLE : View.GONE);
        binding.aboutPage.setVisibility("about".equals(page) ? View.VISIBLE : View.GONE);
        int checked = "configurations".equals(destination) ? R.id.nav_configurations : "settings".equals(destination) ? R.id.nav_settings : "about".equals(destination) ? R.id.nav_about : R.id.nav_connect;
        binding.navigationView.setCheckedItem(checked);
        int title = more ? R.string.more_settings : "configurations".equals(destination) ? R.string.configurations_title : "settings".equals(destination) ? R.string.settings_title : "about".equals(destination) ? R.string.about : R.string.app_name;
        binding.toolbar.setTitle("");
        binding.toolbarTitle.setText(title);
        View visible = more ? binding.moreSettingsPage : "configurations".equals(destination) ? binding.configurationsPage : "settings".equals(destination) ? binding.settingsPage : "about".equals(destination) ? binding.aboutPage : binding.homePage;
        if (ValueAnimator.areAnimatorsEnabled()) {
            visible.setAlpha(0f);
            visible.setTranslationY(12f);
            visible.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private void setupActions() {
        binding.connectButton.setOnClickListener(this::onConnectTapped);
        binding.moreSettingsButton.setOnClickListener(v -> showPage("more_settings"));
         binding.modeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> { if (!checked || restoringSettings) return; preferences.edit().putString("mode", checkedId == R.id.proxy_mode_button ? "manual" : "vpn").apply(); updateModeUi(); });
        binding.splitSwitch.setOnCheckedChangeListener((button, checked) -> { binding.splitContainer.setVisibility(checked ? View.VISIBLE : View.GONE); saveSettings(); });
        binding.routingGroup.setOnCheckedChangeListener((group, checkedId) -> { saveSettings(); updateSelectedCount(); });
        binding.chooseAppsButton.setOnClickListener(v -> openAppSelection());
        binding.resetButton.setOnClickListener(v -> resetDefaults());
        binding.checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        binding.downloadUpdateButton.setOnClickListener(v -> { String status = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""); if ("ready_install".equals(status)) sendBroadcast(new Intent(this, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_INSTALL)); else Toast.makeText(this, AppUpdateManager.startDownload(this, false) ? R.string.update_download_started : R.string.update_download_failed, Toast.LENGTH_SHORT).show(); });
        binding.autoDownloadSwitch.setOnCheckedChangeListener((button, checked) -> { getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, checked).apply(); AppUpdateManager.setAutomaticChecks(this, checked); if (checked) checkForUpdates(); });
        binding.autoConnectSwitch.setOnCheckedChangeListener((button, checked) -> { if (!restoringSettings) preferences.edit().putBoolean("autoConnectAtStart", checked).apply(); });
        binding.mtuModeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            boolean automatic = checkedId == R.id.mtu_automatic_button;
            binding.mtuLayout.setVisibility(automatic ? View.GONE : View.VISIBLE);
            binding.mtuSummary.setText(automatic ? R.string.mtu_automatic_summary : R.string.mtu_manual_summary);
            saveSettings();
        });
        binding.lanSwitch.setOnCheckedChangeListener((button, checked) -> { if (restoringSettings) return; preferences.edit().putBoolean("lanEnabled", checked).apply(); saveSettings(); if ("connected".equals(state)) startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_SET_LAN).putExtra("enabled", checked).putExtra("port", 18190)); updateLanLabel(); });
        binding.copyLanAddressButton.setOnClickListener(v -> copyLanValue(false));
        binding.copyLanPortButton.setOnClickListener(v -> copyLanValue(true));
        binding.exportSettingsButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, "aethon-settings-backup.json"), EXPORT_REQUEST));
        binding.importSettingsButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_REQUEST));
        binding.notificationSettingsButton.setOnClickListener(v -> openNotificationSettings());
        binding.addTileButton.setOnClickListener(v -> requestQuickSettingsTile());
        binding.telegramCard.setOnClickListener(v -> openTelegram());
    }

    private void restoreSettings() {
        restoringSettings = true;
        try {
        migrateLegacySmartSelection();
        String mode = preferences.getString("mode", "vpn");
        binding.modeGroup.check("manual".equals(mode) ? R.id.proxy_mode_button : R.id.vpn_mode_button);
        if (binding.protocolInput.getAdapter() instanceof ProtocolAdapter) ((ProtocolAdapter) binding.protocolInput.getAdapter()).refresh();
        setProtocolSelection(displayedProtocolIndex());
        setSelection(binding.scanInput, "scan", ConnectionDefaults.SCAN_INDEX, R.array.scan_labels);
        setSelection(binding.transportInput, "transport", ConnectionDefaults.TRANSPORT_INDEX, R.array.transport_labels);
        setSelection(binding.ipInput, "ip", 0, R.array.ip_labels);
        setSelection(binding.obfuscationInput, "obfuscation", ConnectionDefaults.OBFUSCATION_INDEX, R.array.obfuscation_labels);
        setSelection(binding.themeInput, "theme", 0, R.array.theme_labels);
        setSelection(binding.languageInput, "fa".equals(preferences.getString("language", "en")) ? 1 : 0, R.array.language_labels);
        binding.socksInput.setText(preferences.getString("socks", getString(R.string.default_socks_address)));
        binding.peerInput.setText(preferences.getString("peer", "")); binding.mtuInput.setText(preferences.getString("mtu", getString(R.string.default_mtu)));
        boolean automaticMtu = "automatic".equals(VpnConnectionController.normalizedMtuMode(preferences.getString("mtuMode", ConnectionDefaults.MTU_MODE)));
        binding.mtuModeGroup.check(automaticMtu ? R.id.mtu_automatic_button : R.id.mtu_manual_button);
        binding.mtuLayout.setVisibility(automaticMtu ? View.GONE : View.VISIBLE);
        binding.mtuSummary.setText(automaticMtu ? R.string.mtu_automatic_summary : R.string.mtu_manual_summary);
        binding.dnsSwitch.setChecked(preferences.getBoolean("dnsLeak", true)); binding.killswitchSwitch.setChecked(preferences.getBoolean("killSwitch", false)); binding.reconnectSwitch.setChecked(preferences.getBoolean("quickReconnect", true)); binding.autoConnectSwitch.setChecked(preferences.getBoolean("autoConnectAtStart", false)); binding.lanSwitch.setChecked(preferences.getBoolean("lanEnabled", false));
        boolean split = preferences.getInt("routing", 0) >= 2; binding.splitSwitch.setChecked(split); binding.splitContainer.setVisibility(split ? View.VISIBLE : View.GONE); binding.routingGroup.check(preferences.getInt("routing", 2) == 3 ? R.id.exclude_apps_radio : R.id.include_apps_radio); updateModeUi(); updateSelectedCount();
        coreSettingsUi.restore();
        } finally { restoringSettings = false; }
        updateModeUi();
    }

    private void setSelection(MaterialAutoCompleteTextView view, String key, int fallback, int arrayId) { setSelection(view, preferences.getInt(key, fallback), arrayId); }
    private void setSelection(MaterialAutoCompleteTextView view, int index, int arrayId) { String[] values = getResources().getStringArray(arrayId); index = Math.max(0, Math.min(values.length - 1, index)); view.setText(values[index], false); view.setTag(index); }

    /**
     * Shows the label for a persisted protocol storage index, clamped to what the current
     * display order actually offers: when a combined privacy entry is not offered in the current
     * mode (Proxy mode hides them) the selection falls back to the base protocol it stands for.
     */
    private void setProtocolSelection(int storageIndex) {
        List<Integer> order = displayOrder();
        String[] values = getResources().getStringArray(R.array.protocol_labels);
        int index = Math.max(0, Math.min(values.length - 1, storageIndex));
        if (!order.contains(index)) {
            // Proxy mode (or an unverified Tor gate): a hidden combined entry degrades to its
            // base protocol instead of being shown as an unavailable choice; the stored
            // preference itself is left untouched so switching back restores it (CHANGE 5).
            index = PsiphonChainRouting.combinedBaseIndex(index);
            if (index < 0) index = TorChainRouting.combinedTorBaseIndex(index);
            if (index < 0 || !order.contains(index)) index = ProtocolOrder.MASQUE;
        }
        binding.protocolInput.setText(values[index], false);
        binding.protocolInput.setTag(index);
    }

    private void updateModeUi() {
        String mode = preferences.getString("mode", "vpn");
        int stored = Math.max(0, Math.min(4, storedProtocolIndex()));
        boolean smart = stored == ProtocolOrder.SMART;
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        boolean combined = "chain".equals(CoreSettings.string(values, "psiphonMode"));
        boolean torChain = TorChainRouting.chainActive(values);
        boolean proxy = ProxyMode.enabled(mode);
        binding.modeSummary.setText(proxy ? getString(R.string.proxy_connection_summary)
                : getString(smart ? R.string.smart_mode_summary : R.string.status_ready_message));
        binding.modeSummary.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        binding.socksInput.setEnabled(!proxy);
        updateProxyEndpoints();
        // dev.017 CHANGE 5: the protocol entry list itself is mode-dependent (Proxy mode hides
        // every internal full-device privacy combination), so the adapter is rebuilt here and the
        // selection re-rendered from the persisted value without losing it.
        // dev.019 ISSUE 4/6: the rebuild is POSTED to the next frame. Rebuilding the adapter
        // synchronously inside the item-click dispatch (or a language/theme recreate) is what
        // made later taps land on a stale popup row set and made selectors appear locked with a
        // single option; posted, the popup is already dismissed and the click fully consumed
        // before the adapter changes.
        if (binding.protocolInput.getAdapter() instanceof ProtocolAdapter) {
            binding.protocolInput.post(() -> {
                if (binding == null) return;
                ((ProtocolAdapter) binding.protocolInput.getAdapter()).refresh();
                setProtocolSelection(displayedProtocolIndex());
            });
        }
        binding.protocolLayout.setVisibility(View.VISIBLE);
        binding.scanLayout.setVisibility(View.VISIBLE);
        // dev.020 CHANGE 3: the MASQUE connection method now lives on the PRIMARY
        // Configurations page between Protocol and Scan Mode, and shows while the selected
        // protocol actually uses the MASQUE transport. The persisted "protocol" value is the
        // BASE protocol, and combined entries 5/6/7 stand for MASQUE/WireGuard/gool
        // respectively, so visibility follows the base protocol of the selection, not the raw
        // index. MIM is included: the audit for dev.020 confirmed the existing "transport"
        // preference genuinely controls MIM as well (CoreSettings.masque() covers mim and
        // AETHER_MASQUE_HTTP2 applies to it), so the same single control is the clear owner
        // for MIM too. Smart Connect decides its own transport and hides the row.
        // dev.034: Gool over MASQUE also rides a MASQUE carrier (v2.3.0 honors
        // AETHER_MASQUE_HTTP2 for its outer tunnel), so the method row shows for it while
        // Classic Gool (a pure WireGuard chain) does not.
        boolean goolBase = ProtocolOrder.baseIndex(stored) == ProtocolOrder.GOOL;
        boolean goolMasque = goolBase && !"classic".equals(CoreSettings.string(values, "goolMode"));
        boolean masqueBase = ProtocolOrder.masqueTransportApplicable(stored) || goolMasque;
        binding.transportLayout.setVisibility(smart || (!masqueBase) ? View.GONE : View.VISIBLE);
        // dev.034: the Gool Mode selector shows directly below Protocol whenever the selected
        // base protocol is Gool (plain or combined with Psiphon/Tor). Switching to a non-Gool
        // protocol hides the row but never erases the saved mode.
        binding.goolModeLayout.setVisibility(goolBase ? View.VISIBLE : View.GONE);
        if (coreSettingsUi != null) coreSettingsUi.updateDependencies();
        // Smart Connect cannot carry a user-pinned Psiphon or Tor combination; keep them exclusive.
        if (combined && smart) {
            preferences.edit().putString("psiphonMode", "off").apply();
        }
        if (torChain && smart) {
            preferences.edit().putString("torMode", "side").apply();
        }
        updateHomeLocationUi();
    }

    private String proxyEndpointSummary() {
        return getString(R.string.proxy_mode_summary, ProxyMode.HTTP_ADDRESS, ProxyMode.SOCKS_ADDRESS);
    }

    private void updateProxyEndpoints() {
        if (binding == null) return;
        binding.proxyEndpoints.setText(proxyEndpointSummary());
    }

    /**
     * dev.017 CHANGE 14 + dev.019 ISSUES 3/5: the Home LOCATION area.
     *
     * <p>+ Psiphon: the compact Exit Country selector (flag + two-letter code closed; 🌐 AUTO for
     * Automatic), always visible — before, during and after connection (ISSUE 5). The same
     * psiphonRegion preference backs both Home and Configurations, so a change from either side
     * is immediately visible in the other (two-way synchronization) and persists.
     *
     * <p>+ Tor (ISSUE 3): NO country selector exists — the Tor exit is chosen by the Tor network.
     * Disconnected/Connecting: the LOCATION row shows no fake or stale country (it collapses to
     * the unavailable hint). Connected: it shows the REAL detected final Tor exit, read-only,
     * refreshed by the service's location lookup (Tor exits rotate, so reconnects update it).
     * The value comes from the tunnel's own egress trace — never the WARP underlay country and
     * never an old Psiphon setting.
     *
     * <p>Plain protocols: no editable Psiphon selector; the row is the read-only detected
     * location exactly as before.
     */
    private void updateHomeLocationUi() {
        if (binding == null) return;
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        boolean psiphonSelector = PsiphonSettingsUi.homeCountrySelectorActive(values);
        boolean torChain = TorChainRouting.chainActive(values);
        boolean proxy = ProxyMode.enabled(preferences.getString("mode", "vpn"));
        boolean selector = psiphonSelector && !proxy;
        boolean connected = "connected".equals(state);
        // ISSUES 3/5: the LOCATION area is always visible for a + Psiphon protocol (the compact
        // selector must show BEFORE, DURING and after connection). For + Tor it appears only
        // while connected (the real detected Tor exit, read-only — never a fake country); for
        // plain protocols it is the read-only detected location while connected, as before.
        binding.locationLayout.setVisibility(selector || connected ? View.VISIBLE : View.GONE);
        binding.locationValue.setEnabled(selector);
        binding.locationValue.setContentDescription(getString(selector
                ? R.string.location_selector_hint : torChain
                ? R.string.tor_location_read_only : R.string.connection_location_unavailable));
        if (torChain && !selector) {
            // + Tor while not connected: no truthful value exists yet, so no country is shown —
            // never a stale or fake one (ISSUE 3). Connected renders through renderState().
            if (!connected) binding.locationValue.setText(R.string.connection_location_unavailable);
            return;
        }
        if (!selector || connected) return;
        // Disconnected: show the saved selection compactly (flag + code, or AUTO for Automatic).
        String region = PsiphonSettingsUi.savedRegion(values);
        binding.locationValue.setText((region.isEmpty()
                ? getString(R.string.psiphon_region_auto_compact)
                : PsiphonCountries.flag(region) + " " + region) + " ▾");
    }

    /** Home location row tap: only the + Psiphon selector opens the country list (ISSUE 3/5). */
    public void onHomeLocationTapped(View view) {
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        if (!PsiphonSettingsUi.homeCountrySelectorActive(values)) return;
        if (ProxyMode.enabled(preferences.getString("mode", "vpn"))) return;
        buildCountryMenu().show();
    }

    /**
     * dev.020 CHANGE 2: the country list as a content-conscious compact anchored popup.
     * dev.019's redesign was already a ListPopupWindow anchored below the LOCATION row, but at
     * ~60% of screen width it read as a broad menu. The dev.020 popup is visibly NARROWER: the
     * width is measured from the actual row content (the longest localized country label plus
     * the AUTO row, both with flag and padding), clamped to a small floor/ceiling fraction of
     * the screen, so the smallest practical width is chosen while normal localized country
     * names never clip. The behavior dev.019 established is kept exactly: opens directly BELOW
     * the LOCATION row, drops downward, scrollable, height-capped, full country names while
     * open, flag + two-letter code closed. The first row is exactly "🌐 AUTO" — the
     * "— Automatic" second label is gone (English and Persian both: the localized compact AUTO
     * string alone). RTL keeps the anchor-side alignment because the width no longer depends on
     * the text direction.
     */
    private android.widget.ListPopupWindow buildCountryMenu() {
        java.util.Locale appLanguage = java.util.Locale.getDefault();
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        String autoRow = getString(R.string.psiphon_region_auto_compact);
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<String> codes = new ArrayList<>();
        // dev.020 CHANGE 2: the first row is exactly the compact AUTO label (e.g. "🌐 AUTO") —
        // never "AUTO — Automatic"; no second label is appended after AUTO.
        labels.add(autoRow);
        codes.add("");
        for (String code : PsiphonCountries.codes()) {
            labels.add(PsiphonCountries.label(code, appLanguage));
            codes.add(code);
        }
        // The trailing Manual row (dev.019 behavior) is preserved: it navigates to the
        // Configurations page where the two-letter code field lives.
        labels.add(getString(R.string.psiphon_region_manual));
        codes.add("");
        android.widget.ListPopupWindow popup = new android.widget.ListPopupWindow(this);
        popup.setAnchorView(binding.locationValue);
        // Open below the anchor and drop downward; never cover the upper half of Home.
        popup.setDropDownGravity(android.view.Gravity.BOTTOM | android.view.Gravity.START);
        popup.setInputMethodMode(android.widget.ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        popup.setWidth(countryMenuWidth(labels));
        // Show a limited number of rows at once; the remainder scrolls (setHeight measures the
        // content and ListPopupWindow caps at the available space below the anchor).
        popup.setHeight(getResources().getDisplayMetrics().heightPixels / 3);
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            if (position == labels.size() - 1) {
                // Manual from Home opens the Configurations page where the code field lives;
                // the Home footprint stays a compact selector.
                showPage("configurations");
                if (binding.psiphonRegionLayout != null) {
                    binding.psiphonRegionLayout.setVisibility(View.VISIBLE);
                    binding.regionManualLayout.setVisibility(View.VISIBLE);
                }
            } else {
                preferences.edit().putString("psiphonRegion", codes.get(position)).apply();
                // Two-way sync: refresh the Configurations selector from the same persisted value.
                if (coreSettingsUi != null) coreSettingsUi.refreshAfterExternalChange();
                updateHomeLocationUi();
            }
            popup.dismiss();
        });
        return popup;
    }

    /**
     * Content-conscious popup width (dev.020 CHANGE 2): the longest row actually rendered,
     * measured with the popup's own item text paint, plus horizontal padding headroom. Clamped
     * between a small floor (so a short-language device does not get a needle) and a ceiling of
     * 45% of screen width — clearly narrower than dev.019's 60% — so normal localized country
     * names never clip while the popup never becomes a broad screen-wide menu.
     */
    private int countryMenuWidth(java.util.List<String> labels) {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        android.widget.TextView measure = new android.widget.TextView(this);
        float density = metrics.density;
        measure.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        float densityScaledPadding = 48f * density;
        float longest = 0f;
        for (String label : labels) {
            longest = Math.max(longest, measure.getPaint().measureText(label));
        }
        int content = (int) (longest + densityScaledPadding);
        int floor = Math.round(metrics.widthPixels * 0.28f);
        int ceiling = Math.round(metrics.widthPixels * 0.45f);
        return Math.max(floor, Math.min(ceiling, content));
    }

    private void migrateLegacySmartSelection() {
        if (!"smart".equals(preferences.getString("mode", "vpn"))) return;
        preferences.edit().putString("mode", "vpn").putInt("protocol", 3).apply();
    }

    /**
     * Fresh-install establishment of the default Protocol (dev.019 ISSUE 8). When the
     * benchmark-selected default is a combined privacy entry, the persisted chain state is
     * materialized once on first launch (a missing "protocol" preference is the fresh-install
     * marker; an existing user's saved protocol — any value — is never touched). Upgrades from
     * dev.018 and earlier always carry a stored protocol, so this path never fires for them.
     */
    private void establishDefaultProtocolOnFirstRun() {
        if (preferences.contains("protocol")) return;
        int defaultIndex = ConnectionDefaults.PROTOCOL_INDEX;
        int psiphonBase = PsiphonChainRouting.combinedBaseIndex(defaultIndex);
        if (psiphonBase >= 0) PrivacyChainState.selectPsiphonChain(preferences, psiphonBase);
        else {
            int torBase = TorChainRouting.combinedTorBaseIndex(defaultIndex);
            if (torBase >= 0) PrivacyChainState.selectTorChain(preferences, torBase);
            else PrivacyChainState.selectPlainProtocol(preferences, defaultIndex);
        }
    }

    /**
     * Selecting a combined Protocol entry activates the matching chain with that base protocol,
     * and selecting any plain entry turns the chains off again: the Protocol selector is the
     * single primary way users activate Psiphon (dev.016) or full-device Tor (dev.017 CHANGE 7).
     * The saved {@code protocol} preference keeps storing the base protocol so older builds,
     * backups and the service contract are unchanged.
     *
     * <p>dev.019 ISSUE 1: every branch applies the COMPLETE privacy-chain state atomically via
     * {@link PrivacyChainState} (one commit, explicit values for both chain families and the Tor
     * switch). dev.018 left {@code torProxy=true} behind after a +Tor → +Psiphon transition, so
     * the mutual-exclusion guard in {@code PrivacySettings.invalid()} rejected every subsequent
     * connect with the generic "advanced setting" error until Reset Defaults or Clear Data.
     */
    private void applyCombinedProtocolSelection(int selection) {
        int combined = PsiphonChainRouting.combinedBaseIndex(selection);
        if (combined >= 0) {
            PrivacyChainState.selectPsiphonChain(preferences, combined);
            if (coreSettingsUi != null) coreSettingsUi.refreshAfterExternalChange();
            return;
        }
        int tor = TorChainRouting.combinedTorBaseIndex(selection);
        if (tor >= 0) {
            // A + Tor entry activates the full-device Tor chain and turns Psiphon completely off:
            // the two chains are mutually exclusive exits.
            PrivacyChainState.selectTorChain(preferences, tor);
            if (coreSettingsUi != null) coreSettingsUi.refreshAfterExternalChange();
            return;
        }
        // dev.019 ISSUE 4: a plain entry is applied unconditionally, not only when a chain was
        // previously active. dev.018 wrote the protocol only through saveSettings()'s derived
        // path, which raced the adapter rebuild; applying it here makes every pairwise base
        // switch immediate, atomic and listener-order-independent.
        PrivacyChainState.selectPlainProtocol(preferences, ProtocolOrder.baseIndex(selection));
        if (coreSettingsUi != null) coreSettingsUi.refreshAfterExternalChange();
    }

    /** Dropdown index for the saved state: the combined entry when Chain is active. */
    private int displayedProtocolIndex() {
        int base = Math.max(0, Math.min(10, preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX)));
        Map<String, Object> values = CoreSettings.values(preferences.getAll());
        if (TorChainRouting.chainActive(values)) return TorChainRouting.combinedTorProtocolIndex(base);
        if ("chain".equals(CoreSettings.string(values, "psiphonMode")) && (base == 0 || base == 1 || base == 2))
            return PsiphonChainRouting.combinedProtocolIndex(base);
        return base;
    }

    /** The base protocol a combined dropdown selection stands for, persisted in the {@code protocol} key. */
    private int storedProtocolIndex() {
        return ProtocolOrder.baseIndex(selectedIndex(binding.protocolInput));
    }

    /**
     * Every tap used to reach the service, and each ACTION_START tore the tunnel down and rebuilt
     * it. The control is now latched for the debounce window while the service commits, and the
     * service itself ignores a start that matches the running configuration.
     */
    private void onConnectTapped(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        long now = SystemClock.elapsedRealtime();
        if (now - lastConnectActionAt < CONNECT_DEBOUNCE_MS) return;
        lastConnectActionAt = now;
        binding.connectButton.setEnabled(false);
        updateHandler.removeCallbacks(connectButtonRelease);
        updateHandler.postDelayed(connectButtonRelease, CONNECT_DEBOUNCE_MS);
        if (shouldDisconnect()) disconnect(); else connect();
    }

    private void connect() {
        if (!coreSettingsUi.validate()) { showPage("configurations"); return; }
        boolean proxy = ProxyMode.enabled(preferences.getString("mode", "vpn"));
        if (!proxy && !validSocks(text(binding.socksInput))) { showPage("configurations"); binding.socksInput.setError(getString(R.string.invalid_socks)); configurationSections.reveal(binding.socksInput); return; }
        if (!"automatic".equals(VpnConnectionController.normalizedMtuMode(preferences.getString("mtuMode", ConnectionDefaults.MTU_MODE))) && !validMtu(text(binding.mtuInput))) { showPage("configurations"); binding.mtuInput.setError(getString(R.string.invalid_mtu)); configurationSections.reveal(binding.mtuInput); return; }
        if (!proxy && binding.splitSwitch.isChecked() && selectedPackages().isEmpty() && binding.routingGroup.getCheckedRadioButtonId() == R.id.include_apps_radio) { showPage("configurations"); configurationSections.reveal(binding.chooseAppsButton); Toast.makeText(this, R.string.split_include_empty, Toast.LENGTH_LONG).show(); return; }
        saveSettings();
        if (!"manual".equals(preferences.getString("mode", "vpn"))) { Intent permission = VpnService.prepare(this); if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return; } }
        VpnConnectionController.connect(this, preferences);
    }

    private void disconnect() { VpnConnectionController.disconnect(this); }

    private void resolveAutoConnectAtStart() {
        if (!autoConnectPending || "checking".equals(state)) return;
        autoConnectPending = false;
        if (VpnConnectionController.shouldAutoConnect(preferences.getBoolean("autoConnectAtStart", false), state)) {
            binding.root.post(this::connect);
        }
    }

    private void openAppSelection() {
        String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps";
        startActivityForResult(new Intent(this, AppSelectionActivity.class).putExtra(AppSelectionActivity.EXTRA_PACKAGES, preferences.getString(key, "")), APPS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == VPN_REQUEST) { if (resultCode == RESULT_OK) VpnConnectionController.connect(this, preferences); else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show(); } else if (requestCode == APPS_REQUEST) { if (data != null && data.getBooleanExtra(AppSelectionActivity.EXTRA_RETURN_HOME, false)) showPage("connect"); else if (resultCode == RESULT_OK && data != null) { String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; preferences.edit().putString(key, data.getStringExtra(AppSelectionActivity.EXTRA_PACKAGES)).apply(); updateSelectedCount(); saveSettings(); } } else if (resultCode == RESULT_OK && data != null && requestCode == EXPORT_REQUEST) handleBackup(requestCode, data.getData()); else if (resultCode == RESULT_OK && data != null && requestCode == IMPORT_REQUEST) new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.restore_settings).setMessage(R.string.restore_confirmation).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.restore_settings, (dialog, which) -> handleBackup(requestCode, data.getData())).show(); }

    private void handleBackup(int requestCode, Uri uri) {
        try {
            if (requestCode == EXPORT_REQUEST) {
                JSONObject out = new JSONObject(); out.put("format", "Aethon Backup v1").put("schema", 1).put("backupVersion", 1);
        for (String key : new String[]{"mode","protocol","scan","transport","ip","obfuscation","theme","language","routing","splitIncludeApps","splitExcludeApps","splitApps","splitEnabled","dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","mtuMode","mtu","lanEnabled","lanPort","ech","h2Fragment","h2FragmentSize","h2FragmentDelay","noDataCheck","validateSecs","startupSecs","reconnectSecs","wiwOuterPeer","wiwInnerPeer","team","accessEmail","accessToken","gateway","upstreamProxy","wgNoProfileRetry","routeSniff","routeSniffMs"}) { Object value = preferences.getAll().get(key); if (value != null && !AndroidCoreSettings.secret(key)) out.put(key, value); }
                for (java.util.Map.Entry<String, Object> entry : CoreSettingsBackup.exportValues(preferences.getAll()).entrySet()) out.put(entry.getKey(), entry.getValue());
                out.put("splitEnabled", preferences.getInt("routing", 0) >= 2);
                out.put("automaticUpdates", getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) { if (stream == null) throw new IllegalStateException(); stream.write(out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show();
            } else {
                StringBuilder json = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), java.nio.charset.StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) json.append(line); }
                JSONObject input = new JSONObject(json.toString());
                if (input.optInt("schema", input.optInt("backupVersion", -1)) != 1) throw new IllegalArgumentException("Unsupported backup schema");
                SharedPreferences.Editor edit = preferences.edit();
                Set<String> allowed = new LinkedHashSet<>(java.util.Arrays.asList("mode","protocol","scan","transport","ip","obfuscation","theme","language","routing","splitIncludeApps","splitExcludeApps","splitApps","splitEnabled","dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","mtuMode","mtu","lanEnabled","lanPort","ech","h2Fragment","h2FragmentSize","h2FragmentDelay","noDataCheck","validateSecs","startupSecs","reconnectSecs","wiwOuterPeer","wiwInnerPeer","team","accessEmail","accessToken","gateway","upstreamProxy","wgNoProfileRetry","routeSniff","routeSniffMs"));
                allowed.removeAll(CoreSettings.DEFAULTS.keySet());
                java.util.Map<String, Object> coreBackup = new java.util.LinkedHashMap<>();
                for (String key : CoreSettings.DEFAULTS.keySet()) if (input.has(key)) {
                    coreBackup.put(key, input.get(key));
                }
                java.util.Map<String, Object> coreImport = CoreSettingsBackup.restoreValues(preferences.getAll(), coreBackup);
                String invalidCore = CoreSettings.invalid(coreImport, null, "h2");
                // A credential-free organization backup can be restored, then credentials entered before Connect.
                if (invalidCore != null && !invalidCore.equals("accessToken") && !invalidCore.equals("accessClientId") && !invalidCore.equals("accessClientSecret")) throw new IllegalArgumentException("Invalid backup Core settings");
                org.json.JSONArray names = input.names();
                Set<String> booleans = new LinkedHashSet<>(java.util.Arrays.asList("dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","lanEnabled","splitEnabled","h2Fragment","noDataCheck","gateway","wgNoProfileRetry","routeSniff"));
                Set<String> integers = new LinkedHashSet<>(java.util.Arrays.asList("protocol","scan","transport","ip","obfuscation","theme","routing","lanPort","validateSecs","startupSecs","reconnectSecs","routeSniffMs"));
                for (java.util.Map.Entry<String, Object> entry : CoreSettings.DEFAULTS.entrySet()) { if (entry.getValue() instanceof Boolean) booleans.add(entry.getKey()); if (entry.getValue() instanceof Integer) integers.add(entry.getKey()); }
                for (int i = 0; names != null && i < names.length(); i++) { String key = names.getString(i); if (!allowed.contains(key)) continue; Object value = input.get(key); if (booleans.contains(key)) edit.putBoolean(key, input.getBoolean(key)); else if (integers.contains(key)) edit.putInt(key, input.getInt(key)); else if (value instanceof String) edit.putString(key, (String)value); }
                if (input.has("splitEnabled") && !input.optBoolean("splitEnabled", false)) edit.putInt("routing", 0);
                AndroidCoreSettings.store(edit, coreImport);
                edit.apply();
                SharedPreferences updatePreferences = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE);
                if (input.has("automaticUpdates")) {
                    boolean automatic = input.getBoolean("automaticUpdates");
                    updatePreferences.edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, automatic).apply();
                    AppUpdateManager.setAutomaticChecks(this, automatic);
                    binding.autoDownloadSwitch.setChecked(automatic);
                }
                restoreSettings(); applyTheme(preferences.getInt("theme", 0)); applyLanguage(preferences.getString("language", "en"), true); Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception error) { Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show(); }
    }

    /**
     * The service owns the real connection state and only answers ACTION_QUERY asynchronously from
     * onStart(). Rendering a hard "Disconnected" first reported a live tunnel as down, so the last
     * published state is replayed instead and anything still unresolved shows as "Checking".
     */
    private String initialState() {
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String saved = service.getString("state", "");
        if (saved != null && ("connected".equals(saved) || "error".equals(saved) || "blocked".equals(saved)
                || AetherVpnService.isConnectingState(saved))) {
            return saved;
        }
        return service.getBoolean("desiredConnected", false) ? "checking" : "disconnected";
    }

    private void renderState(String newState, String message) {
        if (binding == null) return;
        state = newState == null ? "disconnected" : newState;
        boolean connected = "connected".equals(state);
        boolean checking = "checking".equals(state);
        boolean transitioning = "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state);
        updateHandler.removeCallbacks(stateResolveTimeout);
        if (checking) updateHandler.postDelayed(stateResolveTimeout, STATE_RESOLVE_TIMEOUT_MS);
        binding.connectButton.setEnabled(!"disconnecting".equals(state) && !checking
                && SystemClock.elapsedRealtime() - lastConnectActionAt >= CONNECT_DEBOUNCE_MS);
        String orbLabel = connected ? getString(R.string.disconnect) : checking ? getString(R.string.status_checking) : transitioning ? ("disconnecting".equals(state) ? getString(R.string.disconnecting) : getString(R.string.connecting)) : getString(R.string.connect);
        binding.connectButton.setConnectionState(state, orbLabel);
        binding.connectButton.setContentDescription(orbLabel);
        if (connected && smartSelected && !selectedProtocol.isEmpty()) binding.connectionStatus.setText(getString(R.string.status_connected_via_smart, protocolLabel(selectedProtocol)));
        else binding.connectionStatus.setText(connected ? R.string.status_connected : checking ? R.string.status_checking : transitioning ? ("disconnecting".equals(state) ? R.string.status_disconnecting : R.string.status_connecting) : ("error".equals(state) || "blocked".equals(state) ? R.string.status_error : R.string.status_disconnected));
        binding.statusDot.setBackgroundResource(connected ? R.drawable.status_dot_connected : transitioning || checking ? R.drawable.status_dot_connecting : R.drawable.status_dot);
        binding.progress.setVisibility(View.GONE);
        if (connected) {
            binding.connectionMessage.setVisibility(View.GONE);
            binding.connectionInfo.setVisibility(View.VISIBLE);
            // dev.020 CHANGE 9: TIME becomes visible with the connected statistics, directly
            // below Ping and above LOCATION. On the transition into Connected the last
            // persisted total is read so the row never starts blank before the first stats
            // broadcast; the once-per-second timeTick then keeps it advancing.
            binding.timeRow.setVisibility(View.VISIBLE);
            if (dailyConnectedSeconds < 0L) {
                dailyConnectedSeconds = readPersistedDailySeconds();
                lastDailySecondsAt = SystemClock.elapsedRealtime();
            }
            binding.timeValue.setText(formatConnectedTime(Math.max(0L, dailyConnectedSeconds)));
            updateHandler.removeCallbacks(timeTick);
            updateHandler.post(timeTick);
            // dev.020 CHANGE 6: the connected LOCATION shows only a value built from a real
            // ISO country (flag + code). The Tor marker "T1" and any malformed flag-like
            // fragment degrade to the truthful "Location unavailable" state - never a fake
            // country and never a broken flag. The + Tor path stays read-only
            // (updateHomeLocationUi disables the selector for Tor chains).
            boolean trustworthy = endpoint != null && !endpoint.isEmpty()
                    && AetherVpnService.countryCodesValid(endpoint);
            binding.locationValue.setText(trustworthy ? endpoint : getString(R.string.connection_location_unavailable));
        }
        else if ((transitioning && !"disconnecting".equals(state)) || checking) { binding.connectionMessage.setVisibility(View.GONE); binding.connectionInfo.setVisibility(View.VISIBLE); // dev.020 CHANGE 9: hidden while Connecting (only the Connected state shows TIME) and the tick stops.
            binding.timeRow.setVisibility(View.GONE); updateHandler.removeCallbacks(timeTick); }
        else { boolean showError = "error".equals(state) || "blocked".equals(state); binding.connectionMessage.setText(message == null ? getString(R.string.status_error) : message); binding.connectionMessage.setVisibility(showError ? View.VISIBLE : View.GONE); binding.connectionInfo.setVisibility(View.GONE); binding.timeRow.setVisibility(View.GONE); updateHandler.removeCallbacks(timeTick); }
        // "checking" is a UI-only placeholder; persisting it would outlive the resolve and be
        // replayed as a real state on the next launch.
        if (!checking) preferences.edit().putString("state", state).putString("message", message == null ? "" : message).apply();
        if (!connected) resetStats();
        updateProxyEndpoints();
        updateLanLabel();
        // Connected: the Home LOCATION area shows the real detected final egress (never faked);
        // disconnected: the compact saved exit-country selector.
        updateHomeLocationUi();
    }

    private void updateLanLabel() {
        if (binding == null) return;
        boolean enabled = preferences.getBoolean("lanEnabled", false);
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String address = service.getString("lanAddress", "");
        int port = service.getInt("lanPort", 0);
        boolean active = "connected".equals(state) && !address.isEmpty() && port > 0;
        binding.lanProtocolValue.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.lanCopyActions.setVisibility(active ? View.VISIBLE : View.GONE);
        // The LAN listener now demands SOCKS5 credentials, so the card has to show the session pair
        // or the shared proxy is unusable.
        String user = service.getString("lanUsername", "");
        String secret = service.getString("lanPassword", "");
        binding.lanProtocolValue.setText(user.isEmpty() || secret.isEmpty()
                ? getString(R.string.lan_protocol)
                : getString(R.string.lan_credentials, user, secret));
        if (!enabled) {
            binding.lanAddressValue.setText(R.string.lan_disabled);
        } else if (active) {
            binding.lanAddressValue.setText(getString(R.string.lan_address, address, port));
        } else {
            binding.lanAddressValue.setText(R.string.lan_pending);
        }
    }

    private void copyLanValue(boolean portOnly) {
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String address = service.getString("lanAddress", "");
        int port = service.getInt("lanPort", 0);
        if (address.isEmpty() || port <= 0 || !"connected".equals(state)) return;
        String user = service.getString("lanUsername", "");
        String secret = service.getString("lanPassword", "");
        // Copying the bare address is no longer enough to configure a client, so the address action
        // yields a complete authenticated socks5:// URI instead.
        String value = portOnly ? String.valueOf(port)
                : user.isEmpty() || secret.isEmpty() ? address
                : "socks5://" + user + ":" + secret + "@" + address + ":" + port;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(portOnly ? "LAN port" : "LAN address", value));
        Toast.makeText(this, portOnly ? R.string.copy_port : R.string.copy_address, Toast.LENGTH_SHORT).show();
    }

    private boolean shouldDisconnect() { return "connected".equals(state) || "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state); }

    private void renderStats(Intent intent) {
        if (binding == null) return;
        long tx = Math.max(0, intent.getLongExtra("tx", 0));
        long rx = Math.max(0, intent.getLongExtra("rx", 0));
        animateMetric(binding.uploadValue, formatTraffic(tx));
        animateMetric(binding.downloadValue, formatTraffic(rx));
        long ping = intent.getLongExtra("ping", -1);
        binding.pingValue.setText(getString(R.string.ping_value, ping >= 0 ? getString(R.string.ping_millis, Long.toString(ping)) : getString(R.string.metric_unavailable)));
        // dev.020 CHANGE 9: the stats broadcast carries today's accumulated connected seconds
        // from the service's tracker. The broadcast itself fires on change / every ~5s; the
        // per-second display comes from the local timeTick extrapolation of this value.
        if (intent.hasExtra("dailySeconds")) {
            long daily = intent.getLongExtra("dailySeconds", 0L);
            if (daily != dailyConnectedSeconds) {
                dailyConnectedSeconds = Math.max(0L, daily);
                lastDailySecondsAt = SystemClock.elapsedRealtime();
                if ("connected".equals(state)) binding.timeValue.setText(formatConnectedTime(dailyConnectedSeconds));
            }
        }
    }

    /** HH:MM:SS for the Home TIME value (dev.020 CHANGE 9). */
    private static String formatConnectedTime(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /**
     * The persisted today-total, read from the tracker's own store (dev.020 CHANGE 9). Used on
     * the transition into Connected before the first stats broadcast arrives, so the row never
     * renders a stale zero; the value is usage state and survives Reset Defaults (which clears
     * only the "aether" preferences).
     */
    private long readPersistedDailySeconds() {
        SharedPreferences store = getSharedPreferences(ConnectedTimeTracker.PREFS, MODE_PRIVATE);
        return Math.max(0L, store.getLong(ConnectedTimeTracker.KEY_SECONDS, 0L));
    }

    private void animateMetric(TextView view, String value) {
        if (value.equals(view.getTag())) return;
        view.setTag(value);
        view.animate().cancel();
        view.setAlpha(0.45f);
        view.setScaleX(.96f);
        view.setScaleY(.96f);
        view.setText(value);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024L * 1024L) return getString(R.string.traffic_kilobytes, bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return getString(R.string.traffic_megabytes, bytes / (1024.0 * 1024.0));
        return getString(R.string.traffic_gigabytes, bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void resetStats() {
        binding.uploadValue.setText(R.string.metric_unavailable);
        binding.downloadValue.setText(R.string.metric_unavailable);
        binding.pingValue.setText(getString(R.string.ping_value, getString(R.string.metric_unavailable)));
        binding.locationValue.setText(R.string.connection_location_unavailable);
    }

    private void saveSettings() {
        if (restoringSettings || coreSettingsUi == null) return;
        int routing = binding.splitSwitch.isChecked() ? (binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? 3 : 2) : 0;
        String include = preferences.getString("splitIncludeApps", ""); String exclude = preferences.getString("splitExcludeApps", "");
        String mtuMode = binding.mtuModeGroup.getCheckedButtonId() == R.id.mtu_automatic_button ? "automatic" : "manual";
        String mtu = text(binding.mtuInput);
        if (!validMtu(mtu)) mtu = Integer.toString(VpnConnectionController.parseMtu(mtu));
        preferences.edit().putInt("protocol", storedProtocolIndex()).putInt("scan", selectedIndex(binding.scanInput)).putInt("transport", selectedIndex(binding.transportInput)).putInt("ip", selectedIndex(binding.ipInput)).putInt("obfuscation", selectedIndex(binding.obfuscationInput)).putInt("theme", selectedIndex(binding.themeInput)).putInt("routing", routing).putString("splitApps", routing == 3 ? exclude : include).putString("socks", text(binding.socksInput)).putString("peer", text(binding.peerInput)).putString("mtuMode", mtuMode).putString("mtu", mtu).putBoolean("dnsLeak", binding.dnsSwitch.isChecked()).putBoolean("killSwitch", binding.killswitchSwitch.isChecked()).putBoolean("quickReconnect", binding.reconnectSwitch.isChecked()).putBoolean("autoConnectAtStart", binding.autoConnectSwitch.isChecked()).putBoolean("lanEnabled", binding.lanSwitch.isChecked())
                .apply();
        coreSettingsUi.save();
        // CHANGE 14: the Home LOCATION selector mirrors the persisted Exit Country immediately
        // after any settings change (two-way synchronization with Configurations).
        updateHomeLocationUi();
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception e) { return fallback; }
    }

    private Set<String> selectedPackages() { Set<String> result = new LinkedHashSet<>(); String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; AppSelectionActivity.parsePackages(preferences.getString(key, ""), result); return result; }
    private void updateSelectedCount() { if (binding == null) return; binding.selectedAppsCount.setText(getResources().getQuantityString(R.plurals.app_picker_selected_count, selectedPackages().size(), selectedPackages().size())); }
    private void resetDefaults() {
        // dev.019 ISSUE 9: Reset Defaults now lives at the very END of More Settings (after
        // Help) and asks for a localized confirmation before touching anything. The reset itself
        // is unchanged in spirit from dev.017: clear preferences, re-apply the intentional
        // defaults — the benchmark-selected default Protocol (dev.019 ISSUE 8), Scan Mode Turbo,
        // Psiphon Transport Auto, Tor side mode — while keeping the language and the
        // system-default theme. App data, build/update history and unrelated device data are
        // never touched. An existing user's saved choices are only reset by this explicit,
        // confirmed action, never by an upgrade.
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirmation_title)
                .setMessage(R.string.reset_confirmation_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_defaults, (dialog, which) -> performResetDefaults())
                .show();
    }

    private void performResetDefaults() {
        String language = preferences.getString("language", "en");
        preferences.edit().clear().putString("language", language).putInt("theme", 0).apply();
        AndroidCoreSettings.migrate(preferences);
        // dev.019 ISSUE 8: fresh/reset default Protocol is the benchmark-selected winner
        // (recorded in ConnectionDefaults). Explicit here so reset never depends on the
        // restore-order side effect of a missing preference. A combined default entry
        // establishes its complete chain state atomically (no stale Tor/Psiphon fields).
        int defaultIndex = ConnectionDefaults.PROTOCOL_INDEX;
        int psiphonBase = PsiphonChainRouting.combinedBaseIndex(defaultIndex);
        if (psiphonBase >= 0) PrivacyChainState.selectPsiphonChain(preferences, psiphonBase);
        else {
            int torBase = TorChainRouting.combinedTorBaseIndex(defaultIndex);
            if (torBase >= 0) PrivacyChainState.selectTorChain(preferences, torBase);
            else PrivacyChainState.selectPlainProtocol(preferences, defaultIndex);
        }
        restoreSettings();
        // restoreSettings() reads the persisted values; the just-cleared map has no scan/
        // psiphonTransport/torMode entries so ConnectionDefaults (Turbo, Auto, side) apply.
        saveSettings();
        applyTheme(0);
    }

    private void checkForUpdates() { SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); updates.edit().putString("status", "checking").apply(); renderUpdateState(); binding.checkUpdatesButton.setEnabled(false); AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() { @Override public void onComplete() { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); } @Override public void onError(Throwable error) { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); String detail = error == null ? "" : error.getMessage(); Toast.makeText(MainActivity.this, detail == null || detail.isEmpty() ? getString(R.string.update_failed) : getString(R.string.update_failed) + ": " + detail, Toast.LENGTH_LONG).show(); } }, true); }
    private void renderUpdateState() { if (binding == null) return; SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); String latest = updates.getString(UpdateConfig.KEY_LATEST_VERSION, ""); String status = updates.getString("status", ""); binding.latestVersionValue.setText(latest.isEmpty() ? getString(R.string.not_checked) : latest); int id = "up_to_date".equals(status) ? R.string.update_up_to_date : "available".equals(status) ? R.string.update_available : "downloading".equals(status) ? R.string.update_downloading : "ready_install".equals(status) ? R.string.update_ready_install : "checking".equals(status) ? R.string.update_checking : "download_failed".equals(status) ? R.string.update_download_failed : "verification_failed".equals(status) ? R.string.update_verification_failed : "failed".equals(status) ? R.string.update_failed : R.string.not_checked; binding.updateStatusValue.setText(id); String notes = updates.getString(UpdateConfig.KEY_RELEASE_NOTES, ""); binding.releaseNotesValue.setText(notes); binding.releaseNotesValue.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE); boolean downloading = "downloading".equals(status); int progress = downloading ? AppUpdateManager.downloadProgress(this) : -1; binding.updateProgress.setVisibility(downloading ? View.VISIBLE : View.GONE); binding.updateProgress.setIndeterminate(downloading && progress <= 0); if (progress > 0) binding.updateProgress.setProgress(progress); boolean action = "available".equals(status) || "download_failed".equals(status) || "verification_failed".equals(status) || "ready_install".equals(status); binding.downloadUpdateButton.setVisibility(action ? View.VISIBLE : View.GONE); binding.downloadUpdateButton.setText("ready_install".equals(status) ? R.string.install_update : R.string.download_update); }

    private void applyTheme(int choice) { preferences.edit().putInt("theme", choice).apply(); AppCompatDelegate.setDefaultNightMode(themeMode(choice)); }
    private static int themeMode(int choice) { return choice == 1 ? AppCompatDelegate.MODE_NIGHT_NO : choice == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; }
    private static String normalizedLanguage(String language) { return "fa".equalsIgnoreCase(language) ? "fa" : "en"; }
    private String activeResourceLanguage() {
        if (Build.VERSION.SDK_INT >= 24) return normalizedLanguage(getResources().getConfiguration().getLocales().get(0).getLanguage());
        return normalizedLanguage(getResources().getConfiguration().locale.getLanguage());
    }
    private void applyLayoutDirection(String language) {
        int direction = "fa".equals(normalizedLanguage(language)) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        if (binding != null) binding.getRoot().setLayoutDirection(direction);
        getWindow().getDecorView().setLayoutDirection(direction);
    }
    private void applyLanguage(String language, boolean recreate) {
        String selected = normalizedLanguage(language);
        preferences.edit().putString("language", selected).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected));
        applyLayoutDirection(selected);
        // dev.019 ISSUE 6: language changes must apply immediately. setApplicationLocales()
        // triggers the config change itself on API 33+; on older APIs the explicit recreate()
        // is still required. Posting the recreate keeps it out of the click dispatch (a
        // recreate during the click listener left the dropdown popups of the OLD context
        // attached to the NEW activity — the stale-popup half of the selector-lockup family).
        // onCreate's own mismatch check (which runs before binding exists) stays the authority
        // for the cold-start path; this method never touches binding before it is inflated.
        if (recreate && Build.VERSION.SDK_INT < 33) recreate();
    }
    private void requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_REQUEST); }
    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }
    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            manager.requestAddTileService(new ComponentName(this, AethonTileService.class), getString(R.string.tile_name), Icon.createWithResource(this, R.drawable.ic_aethon_mono), getMainExecutor(), result -> Toast.makeText(this, R.string.tile_add_requested, Toast.LENGTH_SHORT).show());
            return;
        }
        try { startActivity(new Intent("android.settings.QUICK_SETTINGS_SETTINGS")); }
        catch (Exception ignored) { Toast.makeText(this, R.string.tile_add_manual, Toast.LENGTH_LONG).show(); }
    }
    private void openTelegram() {
        Intent direct = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=hamvex"));
        for (String packageName : new String[]{"org.telegram.messenger", "org.telegram.messenger.web"}) {
            try {
                direct.setPackage(packageName);
                startActivity(direct);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/hamvex"))); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, R.string.telegram_fallback, Toast.LENGTH_SHORT).show(); }
    }
    private int selectedIndex(MaterialAutoCompleteTextView view) { Object tag = view.getTag(); return tag instanceof Integer ? (Integer) tag : 0; }
    private String protocolLabel(String protocol) { if ("wg".equals(protocol)) return "WireGuard"; if ("gool".equals(protocol)) return "Gool"; if ("mim".equals(protocol)) return "MASQUE-in-MASQUE"; if ("masque".equals(protocol)) return "MASQUE"; return protocol; }
    private static String safe(String value) { return value == null ? "" : value; }
    private String text(com.google.android.material.textfield.TextInputEditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean validSocks(String value) { int split = value.lastIndexOf(':'); if (split <= 0) return false; try { int port = Integer.parseInt(value.substring(split + 1)); return port > 0 && port <= 65535; } catch (Exception ignored) { return false; } }
    private boolean validMtu(String value) { try { int mtu = Integer.parseInt(value); return mtu >= VpnConnectionController.MIN_MTU && mtu <= VpnConnectionController.MAX_MTU; } catch (Exception ignored) { return false; } }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("page", page);
        if (configurationSections != null) configurationSections.save(out);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStart() { super.onStart(); if (binding == null) return; if (!receiverRegistered) { IntentFilter filter = new IntentFilter(); filter.addAction(AetherVpnService.ACTION_STATUS); filter.addAction(AetherVpnService.ACTION_STATS); filter.addAction(UpdateConfig.ACTION_STATE); ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true; } startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY)); updateHandler.removeCallbacks(updateProgressPoll); updateHandler.post(updateProgressPoll); // dev.020 CHANGE 9: resuming the visible Activity re-arms the per-second TIME tick when (and
    // only when) the tunnel is currently Connected; the value itself always comes from the
    // service's tracker.
    if ("connected".equals(state)) { updateHandler.removeCallbacks(timeTick); updateHandler.post(timeTick); } }
    @Override protected void onStop() { saveSettings(); updateHandler.removeCallbacks(updateProgressPoll); updateHandler.removeCallbacks(stateResolveTimeout); updateHandler.removeCallbacks(connectButtonRelease); updateHandler.removeCallbacks(timeTick); if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; } super.onStop(); }
}
