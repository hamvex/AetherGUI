package com.firstham.aethergui;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import com.firstham.aethergui.databinding.ActivityMainBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PsiphonSettingsUi {
    private final ActivityMainBinding binding;
    private final Runnable changed;
    private String topology = "off";
    private String chainTransport = "auto";
    private String region = "";
    /** Manual entry selected in the dropdown; the persisted value is still the region code. */
    private boolean manualSelected;
    private boolean restoring;

    PsiphonSettingsUi(ActivityMainBinding binding, Runnable changed) {
        this.binding = binding;
        this.changed = changed;
        // dev.017 CHANGE 8: the Psiphon mode selector no longer exists as user-facing UI. The
        // binding stays (backend state and migration still read/write psiphonMode), but it is
        // never shown; Psiphon activation is derived from the selected Protocol entry.
        // dev.019 ISSUE 6: setSimpleItems() attaches MaterialArrayAdapter with its DEFAULT
        // filter, which on every popup open filters items by the current text — a closed
        // selector showing "Auto" reduced the list to exactly that one row, the confirmed
        // "selector locked to a single option" defect (Psiphon Transport and Exit Country).
        // A full-list unfiltered adapter (the same fix MainActivity applies to every other
        // dropdown) keeps all entries available on every open, in every locale.
        binding.psiphonChainTransportInput.setAdapter(fullListAdapter(R.array.psiphon_chain_transport_labels));
        binding.psiphonChainTransportInput.setOnItemClickListener((parent, view, position, identifier) -> {
            chainTransport = PsiphonConfiguration.CHAIN_TRANSPORTS.get(position);
            onChanged();
        });
        // Country selector (dev.016; dev.017 CHANGES 4/14): users pick a flag + country name, or
        // the Manual entry and then a two-letter code of their own. The ISO code that
        // AETHER_PSIPHON_REGION needs is the only persisted value, exactly as before: "Manual"
        // itself never persists, and a non-listed code restores as the Manual entry with the code
        // shown in the compact input.
        rebuildRegionItems();
        binding.psiphonRegionInput.setOnItemClickListener((parent, view, position, identifier) -> {
            if (position == regionItems.size() - 1) {
                // Manual: keep the previously entered code (normalized) so switching does not
                // silently clear it; the input right below commits on a valid two-letter entry.
                manualSelected = true;
                region = PsiphonCountries.normalizeManual(region);
            } else {
                manualSelected = false;
                region = position == 0 ? "" : CODES_SNAPSHOT[position - 1];
                binding.regionManualInput.setText("");
            }
            updateDependencies();
            onChanged();
        });
        binding.regionManualInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable editable) {
                if (restoring) return;
                String code = PsiphonCountries.normalizeManual(editable.toString());
                if (!code.isEmpty()) region = code;
                updateRegionSummary();
                if (!code.isEmpty()) onChanged();
            }
        });
    }

    /**
     * An ArrayAdapter that ALWAYS offers the complete list: the popup filter is overridden to
     * return every entry regardless of the closed selector's current text (dev.019 ISSUE 6 —
     * the shared fix for every dropdown that showed only its selected option after a language
     * or settings interaction).
     */
    private ArrayAdapter<String> fullListAdapter(int arrayId) {
        String[] values = binding.getRoot().getContext().getResources().getStringArray(arrayId);
        return new ArrayAdapter<String>(binding.getRoot().getContext(),
                android.R.layout.simple_list_item_1, new java.util.ArrayList<>(java.util.Arrays.asList(values))) {
            @Override public Filter getFilter() {
                return new Filter() {
                    @Override protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        List<String> snapshot = new ArrayList<>(java.util.Arrays.asList(values));
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
            }
        };
    }

    private List<String> regionItems;
    private static final String[] CODES_SNAPSHOT = PsiphonCountries.codes().toArray(new String[0]);

    private void rebuildRegionItems() {
        Locale appLanguage = Locale.getDefault();
        List<String> labels = new ArrayList<>();
        labels.add(binding.getRoot().getContext().getString(R.string.psiphon_region_automatic));
        for (String code : PsiphonCountries.codes())
            labels.add(PsiphonCountries.label(code, appLanguage));
        labels.add(binding.getRoot().getContext().getString(R.string.psiphon_region_manual));
        regionItems = Collections.unmodifiableList(labels);
        // dev.019 ISSUE 6: full-list adapter (no default filtering) so the country popup always
        // offers every region; the closed selector's text never removes rows.
        binding.psiphonRegionInput.setAdapter(regionAdapter(labels));
    }

    /** Unfiltered adapter for the region list (dev.019 ISSUE 6, see {@link #fullListAdapter}). */
    private ArrayAdapter<String> regionAdapter(List<String> labels) {
        final List<String> items = new ArrayList<>(labels);
        return new ArrayAdapter<String>(binding.getRoot().getContext(),
                android.R.layout.simple_list_item_1, items) {
            @Override public Filter getFilter() {
                return new Filter() {
                    @Override protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        List<String> snapshot = new ArrayList<>(items);
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
            }
        };
    }

    void restore(Map<String, ?> values) {
        restoring = true;
        try {
            topology = CoreSettings.string(values, "psiphonMode");
            chainTransport = CoreSettings.string(values, "psiphonTransport");
            region = PsiphonCountries.normalizeManual(CoreSettings.string(values, "psiphonRegion"));
            manualSelected = !region.isEmpty() && !PsiphonCountries.isKnown(region);
            int transportIndex = PsiphonConfiguration.CHAIN_TRANSPORTS.indexOf(chainTransport);
            String[] transportLabels = binding.getRoot().getResources().getStringArray(R.array.psiphon_chain_transport_labels);
            binding.psiphonChainTransportInput.setText(transportIndex >= 0 ? transportLabels[transportIndex]
                    : binding.getRoot().getContext().getString(R.string.invalid_core_setting), false);
            binding.psiphonRegionInput.setText(regionLabel(), false);
            binding.regionManualInput.setText(manualSelected ? region : "");
        } finally {
            restoring = false;
        }
        updateDependencies();
    }

    /** "Automatic", "Manual: DE" or "🇩🇪 Germany" for the saved code, in the app's language. */
    private String regionLabel() {
        if (region == null || region.isEmpty()) return manualSelected
                ? binding.getRoot().getContext().getString(R.string.psiphon_region_manual)
                : binding.getRoot().getContext().getString(R.string.psiphon_region_automatic);
        if (!PsiphonCountries.isKnown(region)) return binding.getRoot().getContext().getString(R.string.psiphon_region_manual_code, region);
        return PsiphonCountries.label(region, Locale.getDefault());
    }

    void collect(Map<String, Object> values) {
        values.put("psiphonMode", topology);
        values.put("psiphonTransport", chainTransport);
        values.put("psiphonRegion", region == null ? "" : region);
        values.put("psiphonHttp", binding.psiphonHttpSwitch.isChecked());
    }

    /**
     * dev.017 CHANGE 14: the compact closed-state text the Home location selector shows — flag +
     * two-letter code only (🌐 AUTO for Automatic). Shared with Configurations so the two
     * selectors always agree; both write the same persisted {@code psiphonRegion}.
     */
    String compactRegionLabel() {
        if (region == null || region.isEmpty())
            return binding.getRoot().getContext().getString(R.string.psiphon_region_auto_compact);
        return PsiphonCountries.flag(region) + " " + region;
    }

    /** Whether the Home compact selector should be an editable country selector right now. */
    static boolean homeCountrySelectorActive(Map<String, ?> values) {
        return "chain".equals(CoreSettings.string(values, "psiphonMode"));
    }

    /** Persisted region from the shared values map (the Home selector writes here too). */
    static String savedRegion(Map<String, ?> values) {
        return PsiphonCountries.normalizeManual(CoreSettings.string(values, "psiphonRegion"));
    }

    void updateDependencies() {
        // dev.017 CHANGE 8: the topology selector is gone from user-facing UI; the combined
        // Protocol entry is the only activation path. The HTTP listener switch stays hidden for
        // the same reason it was in dev.016: the public 1818 contract is provisioned
        // automatically. Only the verified Chain controls are contextually visible.
        binding.psiphonTopologyLayout.setVisibility(View.GONE);
        binding.psiphonValidationPending.setVisibility(View.GONE);

        // dev.017 CHANGE 5: Proxy mode never runs the internal full-device chains (the Protocol
        // selector hides the combined entries), so the Psiphon transport/country controls fold
        // away there too. The saved values are untouched and return with Device VPN mode.
        boolean proxy = binding.modeGroup.getCheckedButtonId() == R.id.proxy_mode_button;

        boolean chain = "chain".equals(topology) && !proxy;
        binding.psiphonChainTransportLayout.setVisibility(chain ? View.VISIBLE : View.GONE);
        binding.psiphonChainTransportInput.setEnabled(chain);
        binding.psiphonRegionLayout.setVisibility(chain ? View.VISIBLE : View.GONE);
        binding.psiphonRegionInput.setEnabled(chain && PrivacyCapabilities.isAvailable(PrivacyCapabilities.PSIPHON_REGION));
        binding.regionManualLayout.setVisibility(chain && manualSelected ? View.VISIBLE : View.GONE);
        updateRegionSummary();

        binding.psiphonHttpSwitch.setVisibility(View.GONE);

        // dev.019 ISSUE 2: the Tor Routing toggle no longer exists as user-facing UI — Tor
        // participation is derived from the Protocol entry, and PrivacyChainState keeps the
        // persisted fields consistent with it atomically. The switch view is only the hidden
        // compatibility carrier of the preference; it is never shown, never clickable.
        binding.torProxySwitch.setVisibility(View.GONE);
        binding.torProxySwitch.setEnabled(false);
    }

    private void updateRegionSummary() {
        String raw = binding.regionManualInput.getText() == null ? "" : binding.regionManualInput.getText().toString().trim();
        String normalized = PsiphonCountries.normalizeManual(raw);
        binding.regionManualLayout.setHelperText(normalized.isEmpty()
                ? binding.getRoot().getContext().getString(R.string.psiphon_region_manual_help)
                : binding.getRoot().getContext().getString(R.string.psiphon_region_manual_valid, normalized));
    }

    View field(String key) {
        switch (key) {
            case "psiphonMode": return binding.psiphonTopologyInput;
            case "psiphonTransport": return binding.psiphonChainTransportInput;
            case "psiphonRegion": return manualSelected ? binding.regionManualInput : binding.psiphonRegionInput;
            case "psiphonHttp": return binding.psiphonHttpSwitch;
            default: return null;
        }
    }

    private void onChanged() {
        if (!restoring) changed.run();
    }
}
