package com.firstham.aethergui;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import com.firstham.aethergui.databinding.ActivityMainBinding;
import java.util.LinkedHashMap;
import java.util.Map;

/** One binding for restore, save, validation and dependent controls. */
final class CoreSettingsUi {
    private final ActivityMainBinding b;
    private final SharedPreferences prefs;
    private final ConfigurationSections sections;
    private final PsiphonSettingsUi psiphon;
    private final Map<String, View> fields = new LinkedHashMap<>();
    private boolean restoring;

    CoreSettingsUi(ActivityMainBinding binding, SharedPreferences preferences, ConfigurationSections sections) {
        b = binding; prefs = preferences; this.sections = sections;
        psiphon = new PsiphonSettingsUi(binding, () -> { if (!restoring) { save(); updateDependencies(); } });
        fields.put("h2Fragment", b.h2FragmentSwitch); fields.put("h2FragmentSize", b.h2FragmentSizeInput); fields.put("h2FragmentDelay", b.h2FragmentDelayInput);
        fields.put("h2FragmentSni", b.h2FragmentSniSwitch);
        fields.put("noDataCheck", b.noDataCheckSwitch); fields.put("validateSecs", b.validateSecsInput); fields.put("startupSecs", b.startupSecsInput); fields.put("reconnectSecs", b.reconnectSecsInput);
        fields.put("wiwOuterPeer", b.wiwOuterPeerInput); fields.put("wiwInnerPeer", b.wiwInnerPeerInput);
        fields.put("mimOuterPeer", b.mimOuterPeerInput); fields.put("mimInnerPeer", b.mimInnerPeerInput);
        // dev.034: the v2.3.0 Gool topology and carrier controls.
        fields.put("goolInnerPeer", b.goolInnerPeerInput); fields.put("apiFragment", b.apiFragmentSwitch);
        fields.put("echDns", b.echDnsInput); fields.put("echDomain", b.echDomainInput);
        fields.put("tlsCiphers", b.tlsCiphersInput); fields.put("tlsGroups", b.tlsGroupsInput);
        fields.put("grease", b.greaseSwitch); fields.put("tlsVerify", b.tlsVerifySwitch);
        fields.put("enrollAddress", b.enrollAddressInput); fields.put("reprovision", b.reprovisionSwitch);
        fields.put("statsLogging", b.statsLoggingSwitch);
        fields.put("tcpConnectSecs", b.tcpConnectSecsInput); fields.put("h2KeepaliveSecs", b.h2KeepaliveSecsInput);
        fields.put("h2KeepaliveTimeoutSecs", b.h2KeepaliveTimeoutSecsInput);
        fields.put("wgEndpointCooldownSecs", b.wgEndpointCooldownSecsInput); fields.put("wgStaleSecs", b.wgStaleSecsInput);
        fields.put("exitLocationSecs", b.exitLocationSecsInput);
        fields.put("team", b.teamInput); fields.put("accessEmail", b.accessEmailInput); fields.put("accessToken", b.accessTokenInput);
        fields.put("accessClientId", b.accessClientIdInput); fields.put("accessClientSecret", b.accessClientSecretInput); fields.put("gateway", b.gatewaySwitch);
        fields.put("upstreamProxy", b.upstreamProxyInput); fields.put("wgNoProfileRetry", b.wgNoProfileRetrySwitch); fields.put("wgKeepalive", b.wgKeepaliveInput);
        fields.put("routeSniff", b.routeSniffSwitch); fields.put("routeSniffMs", b.routeSniffMsInput); fields.put("routeBlock", b.routeBlockInput); fields.put("routeDirect", b.routeDirectInput);
        fields.put("quicV2", b.quicV2Switch); fields.put("h2Peer", b.h2PeerInput); fields.put("dns", b.dnsInput); fields.put("httpProxy", b.httpProxyInput); fields.put("torProxy", b.torProxySwitch);
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable e) { if (!restoring) { save(); updateDependencies(); } }
        };
        for (View view : fields.values()) {
            if (view instanceof EditText) ((EditText)view).addTextChangedListener(watcher);
            else ((CompoundButton)view).setOnCheckedChangeListener((button, checked) -> { if (!restoring) { save(); updateDependencies(); } });
        }
        b.echCustomInput.addTextChangedListener(watcher);
        b.privacyDisableUnavailable.setOnClickListener(view ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(b.getRoot().getContext())
                        .setTitle(R.string.privacy_disable_unavailable)
                        .setMessage(R.string.privacy_disable_confirmation)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            SharedPreferences.Editor edit = prefs.edit();
                            AndroidCoreSettings.store(edit, PrivacySettings.disableUnavailable(values()));
                            edit.apply();
                            restore();
                        }).show());
    }

    /** Re-reads the persisted privacy fields after code outside this binding changed them. */
    void refreshAfterExternalChange() {
        restore();
    }

    void restore() {
        restoring = true;
        try {
            Map<String, Object> values = CoreSettings.values(prefs.getAll());
            psiphon.restore(values);
            String ech = CoreSettings.string(values, "ech");
            int index = ech.isEmpty() ? 1 : ech.equals("auto") ? 0 : 2;
            b.echInput.setTag(index);
            b.echInput.setText(b.getRoot().getResources().getStringArray(R.array.ech_labels)[index], false);
            b.echCustomInput.setText(index == 2 ? ech : "");
            // dev.034: Gool Mode dropdown (storage contract: 0=Gool over MASQUE, 1=Classic).
            b.goolModeInput.setTag("classic".equals(CoreSettings.string(values, "goolMode")) ? 1 : 0);
            b.goolModeInput.setText(b.getRoot().getResources().getStringArray(R.array.gool_mode_labels)
                    [index(b.goolModeInput)], false);
            // dev.034: Performance Profile dropdown (storage contract: 0=Auto, 1=Low, 2=Medium, 3=High).
            String perf = CoreSettings.string(values, "perfProfile");
            int perfIndex = perf.equals("low") ? 1 : perf.equals("medium") ? 2 : perf.equals("high") ? 3 : 0;
            b.perfProfileInput.setTag(perfIndex);
            b.perfProfileInput.setText(b.getRoot().getResources().getStringArray(R.array.perf_profile_labels)[perfIndex], false);
            for (Map.Entry<String, View> e : fields.entrySet()) {
                Object value = values.get(e.getKey());
                if (e.getValue() instanceof EditText) ((EditText)e.getValue()).setText(value == null ? "" : value.toString());
                else ((CompoundButton)e.getValue()).setChecked(Boolean.TRUE.equals(value));
            }
        } finally { restoring = false; }
        updateDependencies();
    }

    Map<String, Object> values() {
        Map<String, Object> values = CoreSettings.values(prefs.getAll());
        for (Map.Entry<String, View> e : fields.entrySet()) {
            Object value;
            if (e.getValue() instanceof EditText) {
                String raw = ((EditText)e.getValue()).getText().toString().trim();
                value = raw;
                if (CoreSettings.DEFAULTS.get(e.getKey()) instanceof Integer) {
                    try { value = raw.isEmpty() ? CoreSettings.DEFAULTS.get(e.getKey()) : Integer.parseInt(raw); }
                    catch (NumberFormatException invalid) { value = -1; }
                } else if ((e.getKey().equals("h2FragmentSize") || e.getKey().equals("h2FragmentDelay")) && raw.isEmpty()) value = CoreSettings.DEFAULTS.get(e.getKey());
            } else value = ((CompoundButton)e.getValue()).isChecked();
            values.put(e.getKey(), value);
        }
        int index = index(b.echInput);
        values.put("ech", index == 0 ? "auto" : index == 2 ? b.echCustomInput.getText().toString().trim() : "");
        values.put("goolMode", index(b.goolModeInput) == 1 ? "classic" : "masque");
        int perf = index(b.perfProfileInput);
        values.put("perfProfile", perf == 1 ? "low" : perf == 2 ? "medium" : perf == 3 ? "high" : "");
        psiphon.collect(values);
        return values;
    }

    void save() {
        if (restoring) return;
        SharedPreferences.Editor edit = prefs.edit(); AndroidCoreSettings.store(edit, values()); edit.apply();
    }

    boolean validate() {
        String mode = prefs.getString("mode", "vpn");
        Map<String, Object> values = ProxyMode.settings(mode, values());
        String key = CoreSettings.invalid(values, protocol(), index(b.transportInput) == 1 ? "h2" : "h3");
        // dev.017 CHANGE 5: in Proxy mode the internal full-device chains are not offered, and a
        // saved chain state is refused at validation (the stored preference is not rewritten, so
        // returning to Device VPN reactivates it).
        if (key == null) key = PrivacySettings.invalidForMode(values, protocol(), mode);
        if (key == null && PrivacySettings.unavailable(values) != null) {
            sections.reveal(b.privacySavedBlocked);
            android.widget.Toast.makeText(b.getRoot().getContext(), R.string.privacy_saved_blocked,
                    android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (index(b.echInput) == 2 && CoreSettings.string(values, "ech").isEmpty()) key = "ech";
        String socks = ProxyMode.socksAddress(mode, b.socksInput.getText().toString().trim());
        if (key == null && !CoreSettings.string(values, "httpProxy").isEmpty() && (socks.equals(CoreSettings.string(values, "httpProxy"))
                || (CoreSettings.enabled(values, "torProxy") && CoreSettings.string(values, "httpProxy").endsWith(":1821")))) key = "httpProxy";
        if (key == null && CoreSettings.enabled(values, "torProxy") && socks.endsWith(":1821")) key = "torProxy";
        if (key == null) return true;
        // dev.019 ISSUE 1 diagnostics: validation failures log the exact setting key and the
        // privacy-chain state instead of only the generic user-facing message, so any future
        // rejection is diagnosable from logcat.
        android.util.Log.w("AethonSettings", "connect validation failed: setting=" + key
                + " mode=" + mode + " protocol=" + protocol()
                + " " + PrivacyChainState.describe(prefs));
        View view = key.equals("ech") ? b.echCustomInput : fields.get(key);
        if (view == null) view = psiphon.field(key);
        if (view instanceof EditText) ((EditText)view).setError(b.getRoot().getContext().getString(R.string.invalid_core_setting));
        if (view != null) sections.reveal(view);
        android.widget.Toast.makeText(b.getRoot().getContext(), R.string.invalid_core_setting, android.widget.Toast.LENGTH_LONG).show();
        return false;
    }

    void updateDependencies() {
        psiphon.updateDependencies();
        boolean unavailablePrivacy = PrivacySettings.unavailable(values()) != null;
        b.privacySavedBlocked.setVisibility(unavailablePrivacy ? View.VISIBLE : View.GONE);
        b.privacyDisableUnavailable.setVisibility(unavailablePrivacy ? View.VISIBLE : View.GONE);
        b.httpProxyInput.setEnabled(!ProxyMode.enabled(prefs.getString("mode", "vpn")));
        String protocol = protocol(); boolean smart = protocol.equals("smart"); boolean masque = CoreSettings.masque(protocol) || smart;
        Map<String, Object> values = values();
        boolean goolBase = protocol.equals("gool");
        boolean goolMasque = CoreSettings.goolOverMasque(protocol, values);
        boolean carrier = smart || CoreSettings.masqueCarrier(protocol, values);
        boolean h2 = carrier && (index(b.transportInput) == 1 || smart), wg = protocol.equals("wg") || protocol.equals("gool") || smart;
        b.echInput.setEnabled(carrier); b.echCustomLayout.setVisibility(index(b.echInput) == 2 ? View.VISIBLE : View.GONE); b.echCustomInput.setEnabled(carrier);
        // dev.034: ECH Auto's lookup knobs show only in Auto mode (and are inert otherwise,
        // exactly as the Core reads them).
        b.echAutoContainer.setVisibility(index(b.echInput) == 0 ? View.VISIBLE : View.GONE);
        b.echDnsInput.setEnabled(carrier); b.echDomainInput.setEnabled(carrier);
        b.h2FragmentSwitch.setEnabled(h2); b.h2FragmentContainer.setVisibility(b.h2FragmentSwitch.isChecked() && h2 ? View.VISIBLE : View.GONE);
        b.h2FragmentSniSwitch.setVisibility(b.h2FragmentSwitch.isChecked() && h2 ? View.VISIBLE : View.GONE);
        b.h2PeerInput.setEnabled(h2); b.quicV2Switch.setEnabled(masque && (index(b.transportInput) == 0 || smart)); b.startupSecsInput.setEnabled(masque);
        b.apiFragmentSwitch.setEnabled(carrier);
        // dev.034: Gool Mode context. The WiW endpoints exist only in the Classic topology
        // (naming one is what selects Classic in the Core); the inner endpoint only in
        // Gool over MASQUE.
        b.wiwEndpointsContainer.setVisibility((goolBase && !goolMasque) || smart ? View.VISIBLE : View.GONE);
        b.goolInnerContainer.setVisibility(goolBase && goolMasque ? View.VISIBLE : View.GONE);
        b.goolInnerPeerInput.setEnabled(goolBase && goolMasque);
        b.mimEndpointsContainer.setVisibility(protocol.equals("mim") ? View.VISIBLE : View.GONE);
        b.wgNoProfileRetrySwitch.setEnabled(wg); b.wgKeepaliveInput.setEnabled(wg);
        b.wgEndpointCooldownSecsInput.setEnabled(wg); b.wgStaleSecsInput.setEnabled(wg);
        b.h2KeepaliveSecsInput.setEnabled(h2); b.h2KeepaliveTimeoutSecsInput.setEnabled(h2);
        // Exit policy recheck interval: visible while the policy is enabled and available.
        b.exitLocationSecsLayout.setVisibility(CoreSettings.enabled(values, "exitLocationEnabled")
                && PrivacyCapabilities.isAvailable(PrivacyCapabilities.EXIT_LOCATION) ? View.VISIBLE : View.GONE);
        b.routeSniffMsInput.setEnabled(b.routeSniffSwitch.isChecked()); b.validateSecsInput.setEnabled(!b.noDataCheckSwitch.isChecked());
        boolean team = !b.teamInput.getText().toString().trim().isEmpty();
        b.gatewaySwitch.setEnabled(team); b.accessTokenInput.setEnabled(team); b.accessClientIdInput.setEnabled(team); b.accessClientSecretInput.setEnabled(team);
        b.accessEmailInput.setEnabled(false);
        sections.refreshHelp();
    }

    private String protocol() {
        // The dropdown tag stores the STORAGE index; a combined privacy entry (5-10) stands for
        // its base protocol (dev.018 fix for the dev.017 validation defect).
        String[] values = {"masque", "wg", "gool", "smart", "mim"};
        return values[ProtocolOrder.baseIndex(index(b.protocolInput))];
    }
    private static int index(View view) { return view.getTag() instanceof Integer ? (Integer)view.getTag() : 0; }
}
