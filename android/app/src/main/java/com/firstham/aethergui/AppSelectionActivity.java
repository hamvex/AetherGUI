package com.firstham.aethergui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.firstham.aethergui.databinding.ActivityAppSelectionBinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppSelectionActivity extends AppCompatActivity {
    static final String EXTRA_PACKAGES = "packages";
    static final String EXTRA_RETURN_HOME = "return_home";
    private static final String STATE_PACKAGES = "selected_packages";
    private static final String STATE_SHOW_SYSTEM = "show_system";
    private ActivityAppSelectionBinding binding;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private AppAdapter adapter;
    /**
     * dev.020 CHANGE 8: whether system apps are included in the list. Default OFF - the normal
     * user-app experience is unchanged. Persisted per session only (not a configuration
     * preference): the split-tunnel selections themselves are what persist.
     */
    private boolean showSystemApps;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityAppSelectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(binding.appPickerRoot, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        showSystemApps = state != null && state.getBoolean(STATE_SHOW_SYSTEM, false);
        parsePackages(state == null ? getIntent().getStringExtra(EXTRA_PACKAGES) : state.getString(STATE_PACKAGES), selected);
        binding.appPickerToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { returnHome(); }
        });
        binding.appList.setEmptyView(binding.appEmpty);
        binding.appList.setOnItemClickListener((parent, view, position, id) -> toggle(adapter.getItem(position).packageName));
        binding.appSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (adapter != null) adapter.filter(s == null ? "" : s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        // dev.020 CHANGE 8: the Show system apps control. Toggling it reloads the list in the
        // background (never blocking the UI thread - the enumeration is large); the selected
        // set is unaffected, so previously selected system-app entries are never silently
        // deleted merely because they are hidden.
        binding.showSystemAppsSwitch.setChecked(showSystemApps);
        binding.showSystemAppsSwitch.setOnCheckedChangeListener((button, checked) -> {
            showSystemApps = checked;
            if (adapter != null) adapter.setVisibleEntries(userApps, systemApps, showSystemApps);
            updateCount();
        });
        binding.selectAllButton.setOnClickListener(v -> { if (adapter != null) { selected.addAll(adapter.allPackages()); adapter.notifyDataSetChanged(); updateCount(); } });
        binding.clearAllButton.setOnClickListener(v -> { selected.clear(); if (adapter != null) adapter.notifyDataSetChanged(); updateCount(); });
        binding.applyAppsButton.setOnClickListener(v -> {
            Intent result = new Intent().putExtra(EXTRA_PACKAGES, String.join("\n", selected));
            setResult(RESULT_OK, result);
            finish();
        });
        updateCount();
        loadApplications();
    }

    private void loadApplications() {
        loader.execute(() -> {
            PackageManager pm = getPackageManager();
            // dev.020 CHANGE 8: both families are enumerated once in the background. The
            // launcher query is exactly the previous user-app list; getInstalledApplications
            // supplies the system-app families, filtered to real pre-installed packages.
            Map<String, AppEntry> user = new LinkedHashMap<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
                String packageName = info.activityInfo.packageName;
                if (packageName.equals(getPackageName()) || user.containsKey(packageName)) continue;
                CharSequence label = info.loadLabel(pm);
                user.put(packageName, new AppEntry(label == null ? packageName : label.toString(), packageName, info.loadIcon(pm), false, false));
            }
            Map<String, AppEntry> system = new LinkedHashMap<>();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                String packageName = info.packageName;
                // Never offer the Aethon app itself: routing its own traffic through its own
                // tunnel cannot work and could break the VPN's own control traffic.
                if (packageName.equals(getPackageName()) || user.containsKey(packageName) || system.containsKey(packageName)) continue;
                if (!isSelectableSystemApp(info)) continue;
                CharSequence label = pm.getApplicationLabel(info);
                system.put(packageName, new AppEntry(label == null ? packageName : label.toString(), packageName, pm.getApplicationIcon(info), true, false));
            }
            // A saved package that is no longer installed stays visible (read-only style) in
            // whichever family it belongs to, so uninstalled package IDs are handled gracefully
            // and selections are preserved across the dev.019 -> dev.020 upgrade.
            for (String packageName : selected) {
                if (packageName.isEmpty() || user.containsKey(packageName) || system.containsKey(packageName)) continue;
                user.put(packageName, new AppEntry(getString(R.string.app_picker_missing), packageName, pm.getDefaultActivityIcon(), false, true));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                userApps = user;
                systemApps = system;
                if (adapter == null) {
                    adapter = new AppAdapter();
                    binding.appList.setAdapter(adapter);
                }
                adapter.setVisibleEntries(user, system, showSystemApps);
                binding.appLoading.setVisibility(View.GONE);
            });
        });
    }

    /**
     * Which pre-installed packages are offered as selectable system apps. Updated system
     * packages (FLAG_UPDATED_SYSTEM_APP) lost their pre-installed nature and are treated as
     * user apps by the launcher query anyway; the filter keeps genuinely pre-installed
     * packages and excludes nothing else by category - the user explicitly asked for system
     * apps to be selectable, so they are not indiscriminately hidden. The only package that
     * can break Aethon's own tunnel is Aethon itself, which is excluded from both families
     * in loadApplications().
     */
    private static boolean isSelectableSystemApp(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private Map<String, AppEntry> userApps = new LinkedHashMap<>();
    private Map<String, AppEntry> systemApps = new LinkedHashMap<>();

    private void toggle(String packageName) {
        if (!selected.add(packageName)) selected.remove(packageName);
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        binding.selectedCount.setText(getResources().getQuantityString(R.plurals.app_picker_selected_count, selected.size(), selected.size()));
    }

    static void parsePackages(String value, Set<String> output) {
        if (value == null) return;
        for (String packageName : value.split("[\\r\\n,]+")) if (!packageName.trim().isEmpty()) output.add(packageName.trim());
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_PACKAGES, String.join("\n", selected));
        outState.putBoolean(STATE_SHOW_SYSTEM, showSystemApps);
        super.onSaveInstanceState(outState);
    }

    private void returnHome() {
        setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_RETURN_HOME, true));
        finish();
    }

    private static final class AppEntry {
        final String name;
        final String packageName;
        final Drawable icon;
        final boolean system;
        final boolean missing;
        AppEntry(String name, String packageName, Drawable icon, boolean system, boolean missing) { this.name = name; this.packageName = packageName; this.icon = icon; this.system = system; this.missing = missing; }
    }

    /**
     * dev.020 CHANGE 8: the list combines user apps with (optionally) system apps, sorted
     * user apps first, then system apps, each alphabetical, missing entries last within their
     * family. Search runs across BOTH families whenever system apps are visible.
     */
    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> shown = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(AppSelectionActivity.this);

        void setVisibleEntries(Map<String, AppEntry> user, Map<String, AppEntry> system, boolean includeSystem) {
            shown.clear();
            List<AppEntry> users = new ArrayList<>(user.values());
            users.sort(Comparator.comparing((AppEntry item) -> item.missing).thenComparing(item -> item.name.toLowerCase(Locale.ROOT)));
            shown.addAll(users);
            if (includeSystem) {
                List<AppEntry> systems = new ArrayList<>(system.values());
                systems.sort(Comparator.comparing((AppEntry item) -> item.missing).thenComparing(item -> item.name.toLowerCase(Locale.ROOT)));
                shown.addAll(systems);
            }
            notifyDataSetChanged();
        }

        void filter(String raw) {
            String query = raw.trim().toLowerCase(Locale.ROOT);
            List<AppEntry> combined = new ArrayList<>();
            List<AppEntry> users = new ArrayList<>(userApps.values());
            users.sort(Comparator.comparing((AppEntry item) -> item.missing).thenComparing(item -> item.name.toLowerCase(Locale.ROOT)));
            combined.addAll(users);
            if (showSystemApps) {
                List<AppEntry> systems = new ArrayList<>(systemApps.values());
                systems.sort(Comparator.comparing((AppEntry item) -> item.missing).thenComparing(item -> item.name.toLowerCase(Locale.ROOT)));
                combined.addAll(systems);
            }
            shown.clear();
            for (AppEntry item : combined) if (query.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(query) || item.packageName.toLowerCase(Locale.ROOT).contains(query)) shown.add(item);
            notifyDataSetChanged();
        }

        Set<String> allPackages() { Set<String> result = new LinkedHashSet<>(); for (AppEntry item : shown) if (!item.missing) result.add(item.packageName); return result; }
        @Override public int getCount() { return shown.size(); }
        @Override public AppEntry getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return getItem(position).packageName.hashCode(); }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) { convertView = inflater.inflate(R.layout.item_app_picker, parent, false); holder = new Holder(convertView); convertView.setTag(holder); } else holder = (Holder) convertView.getTag();
            AppEntry item = getItem(position);
            holder.icon.setImageDrawable(item.icon); holder.name.setText(item.name); holder.packageName.setText(item.packageName); holder.checked.setChecked(selected.contains(item.packageName));
            holder.checked.setClickable(false); holder.checked.setFocusable(false); convertView.setAlpha(item.missing ? 0.65f : 1f);
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView icon; final TextView name; final TextView packageName; final com.google.android.material.checkbox.MaterialCheckBox checked;
        Holder(View root) { icon = root.findViewById(R.id.app_icon); name = root.findViewById(R.id.app_name); packageName = root.findViewById(R.id.app_package); checked = root.findViewById(R.id.app_selected); }
    }
}
