package com.firstham.aethergui;

import android.content.Intent;
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
    private ActivityAppSelectionBinding binding;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private AppAdapter adapter;

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
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Map<String, AppEntry> unique = new LinkedHashMap<>();
            for (ResolveInfo info : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
                String packageName = info.activityInfo.packageName;
                if (packageName.equals(getPackageName()) || unique.containsKey(packageName)) continue;
                CharSequence label = info.loadLabel(pm);
                unique.put(packageName, new AppEntry(label == null ? packageName : label.toString(), packageName, info.loadIcon(pm), false));
            }
            for (String packageName : selected) {
                if (!packageName.isEmpty() && !unique.containsKey(packageName)) unique.put(packageName, new AppEntry(getString(R.string.app_picker_missing), packageName, pm.getDefaultActivityIcon(), true));
            }
            List<AppEntry> entries = new ArrayList<>(unique.values());
            entries.sort(Comparator.comparing((AppEntry item) -> item.missing).thenComparing(item -> item.name.toLowerCase(Locale.ROOT)));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter = new AppAdapter(entries);
                binding.appList.setAdapter(adapter);
                binding.appLoading.setVisibility(View.GONE);
            });
        });
    }

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
        final boolean missing;
        AppEntry(String name, String packageName, Drawable icon, boolean missing) { this.name = name; this.packageName = packageName; this.icon = icon; this.missing = missing; }
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> all;
        private final List<AppEntry> shown = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(AppSelectionActivity.this);
        AppAdapter(List<AppEntry> entries) { all = entries; shown.addAll(entries); }
        void filter(String raw) { String query = raw.trim().toLowerCase(Locale.ROOT); shown.clear(); for (AppEntry item : all) if (query.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(query) || item.packageName.toLowerCase(Locale.ROOT).contains(query)) shown.add(item); notifyDataSetChanged(); }
        Set<String> allPackages() { Set<String> result = new LinkedHashSet<>(); for (AppEntry item : all) if (!item.missing) result.add(item.packageName); return result; }
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
