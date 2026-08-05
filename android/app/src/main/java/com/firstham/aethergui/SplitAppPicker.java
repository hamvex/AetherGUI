package com.firstham.aethergui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SplitAppPicker {
    interface Listener { void onSelected(Set<String> packages); }

    private static final String[] SECTIONS = buildSections();

    static void show(Context context, Collection<String> saved, Listener listener) {
        PackageManager packageManager = context.getPackageManager();
        Set<String> selected = new LinkedHashSet<>(saved);
        List<AppEntry> entries = loadApps(context, packageManager, selected);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null, false);
        ListView list = root.findViewById(R.id.app_list);
        TextView empty = root.findViewById(R.id.app_empty);
        TextView count = root.findViewById(R.id.selected_count);
        TextInputEditText search = root.findViewById(R.id.app_search);
        AppAdapter adapter = new AppAdapter(context, entries, selected);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = adapter.getItem(position);
            if (!selected.add(entry.packageName)) selected.remove(entry.packageName);
            adapter.notifyDataSetChanged();
            updateCount(context, count, selected);
        });
        updateCount(context, count, selected);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { adapter.filter(value == null ? "" : value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_picker_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.app_picker_done, (dialog, which) -> listener.onSelected(new LinkedHashSet<>(selected)))
                .show();
    }

    private static List<AppEntry> loadApps(Context context, PackageManager pm, Set<String> selected) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(context.getPackageName()) || byPackage.containsKey(packageName)) continue;
            CharSequence label = info.loadLabel(pm);
            byPackage.put(packageName, new AppEntry(label == null ? packageName : label.toString(), packageName, info.loadIcon(pm), false));
        }
        for (String packageName : selected) {
            if (!packageName.isEmpty() && !packageName.equals(context.getPackageName()) && !byPackage.containsKey(packageName)) {
                byPackage.put(packageName, new AppEntry(context.getString(R.string.app_picker_missing), packageName, pm.getDefaultActivityIcon(), true));
            }
        }
        List<AppEntry> result = new ArrayList<>(byPackage.values());
        result.sort(Comparator.comparing((AppEntry entry) -> !entry.missing).thenComparing(entry -> entry.name.toLowerCase(Locale.ROOT)).thenComparing(entry -> entry.packageName));
        return result;
    }

    private static void updateCount(Context context, TextView view, Set<String> selected) {
        view.setText(context.getResources().getQuantityString(R.plurals.app_picker_selected_count, selected.size(), selected.size()));
    }

    private static String[] buildSections() {
        String[] result = new String[27];
        result[0] = "#";
        for (int i = 1; i < result.length; i++) result[i] = String.valueOf((char) ('A' + i - 1));
        return result;
    }

    private static final class AppEntry {
        final String name;
        final String packageName;
        final Drawable icon;
        final boolean missing;
        AppEntry(String name, String packageName, Drawable icon, boolean missing) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.missing = missing;
        }
        char section() {
            if (missing || name.isEmpty()) return '#';
            char value = Character.toUpperCase(name.charAt(0));
            return value >= 'A' && value <= 'Z' ? value : '#';
        }
    }

    private static final class AppAdapter extends BaseAdapter implements SectionIndexer {
        private final LayoutInflater inflater;
        private final List<AppEntry> all;
        private final List<AppEntry> shown = new ArrayList<>();
        private final Set<String> selected;

        AppAdapter(Context context, List<AppEntry> entries, Set<String> selected) {
            inflater = LayoutInflater.from(context);
            all = entries;
            this.selected = selected;
            shown.addAll(entries);
        }

        void filter(String value) {
            String query = value.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (AppEntry entry : all) {
                if (query.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(query) || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) shown.add(entry);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public AppEntry getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return shown.get(position).packageName.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_app_picker, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else holder = (Holder) convertView.getTag();
            AppEntry entry = getItem(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.name.setText(entry.name);
            holder.packageName.setText(entry.packageName);
            holder.selected.setChecked(selected.contains(entry.packageName));
            convertView.setAlpha(entry.missing ? 0.68f : 1f);
            return convertView;
        }

        @Override public Object[] getSections() { return SECTIONS; }

        @Override public int getPositionForSection(int sectionIndex) {
            char section = SECTIONS[Math.max(0, Math.min(SECTIONS.length - 1, sectionIndex))].charAt(0);
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).section() >= section) return i;
            return Math.max(0, shown.size() - 1);
        }

        @Override public int getSectionForPosition(int position) {
            if (shown.isEmpty()) return 0;
            char section = shown.get(Math.max(0, Math.min(shown.size() - 1, position))).section();
            return section == '#' ? 0 : section - 'A' + 1;
        }

        static final class Holder {
            final ImageView icon;
            final TextView name;
            final TextView packageName;
            final com.google.android.material.checkbox.MaterialCheckBox selected;
            Holder(View itemView) {
                icon = itemView.findViewById(R.id.app_icon);
                name = itemView.findViewById(R.id.app_name);
                packageName = itemView.findViewById(R.id.app_package);
                selected = itemView.findViewById(R.id.app_selected);
            }
        }
    }

    private SplitAppPicker() { }
}
