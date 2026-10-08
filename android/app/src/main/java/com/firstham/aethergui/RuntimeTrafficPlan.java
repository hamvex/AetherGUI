package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RuntimeTrafficPlan {
    static List<Endpoint> endpoints(Map<String, ?> settings, String socks) {
        PrivacyRuntimeConfig.validateListeners(settings, socks, null);
        boolean only = "only".equals(CoreSettings.string(settings, "psiphonMode"));
        boolean chain = PsiphonChainRouting.chainActive(settings);
        boolean torChain = TorChainRouting.chainActive(settings);
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, String> listener : PrivacyRuntimeConfig.listenerAddresses(settings, socks).entrySet()) {
            String name = listener.getKey();
            // "Base" is the internal underlay listener that carries the Psiphon helper's upstream
            // while Chain is active: probing it would measure the WARP exit, not the device egress.
            if (name.equals("Base")) continue;
            // The public 1818/1819 ports in Chain mode are byte relays started only after this
            // gate passes; the "Psiphon HTTP" endpoint proves exactly the bytes both relays
            // forward, so the relays themselves are never readiness targets.
            if (chain && (name.equals("HTTP") || name.equals("Public SOCKS5"))) continue;
            if (torChain && (name.equals("HTTP") || name.equals("Public SOCKS5"))) continue;
            // In Chain mode the "SOCKS5" listener is the Psiphon final proxy itself, so it is
            // announcement-gated like every other privacy listener and its proof doubles as the
            // Home-screen location trace: the trace then reports the Psiphon exit, not the WARP
            // underlay, which is exactly the location normal application traffic sees.
            boolean privacy = only || name.startsWith("Tor") || name.startsWith("Psiphon")
                    || ((chain || torChain) && name.equals("SOCKS5"));
            endpoints.add(new Endpoint(name, listener.getValue(), name.contains("HTTP"),
                    name.equals("SOCKS5"), privacy));
        }
        return Collections.unmodifiableList(endpoints);
    }

    static boolean privacyEnabled(Map<String, ?> settings) {
        Map<String, Object> values = CoreSettings.values(settings);
        return CoreSettings.enabled(values, "torProxy")
                || !"off".equals(CoreSettings.string(values, "psiphonMode"));
    }

    static final class Endpoint {
        final String name;
        final String address;
        final boolean http;
        final boolean primary;
        final boolean privacy;

        Endpoint(String name, String address, boolean http, boolean primary, boolean privacy) {
            this.name = name;
            this.address = address;
            this.http = http;
            this.primary = primary;
            this.privacy = privacy;
        }
    }

    static final class Proof {
        private final Set<Endpoint> required;
        private final Set<Endpoint> proven = new HashSet<>();

        Proof(List<Endpoint> endpoints) {
            if (endpoints.isEmpty()) throw new IllegalArgumentException("No traffic endpoints selected");
            required = new HashSet<>(endpoints);
        }

        boolean record(Endpoint endpoint) {
            if (!required.contains(endpoint)) throw new IllegalArgumentException("Traffic proof belongs to another plan");
            proven.add(endpoint);
            return proven.containsAll(required);
        }
    }

    static final class Announcements {
        private final Map<String, String> expected = new LinkedHashMap<>();
        private final Set<String> ready = new HashSet<>();

        Announcements(Map<String, ?> settings, String socks) {
            String topology = CoreSettings.string(settings, "psiphonMode");
            boolean chain = PsiphonChainRouting.chainActive(settings);
            boolean torChain = TorChainRouting.chainActive(settings);
            for (Endpoint endpoint : endpoints(settings, socks)) {
                if (!endpoint.privacy) continue;
                String message;
                switch (endpoint.name) {
                    case "Tor": message = "tor is ready; " + endpoint.address + " leaves through tor, carried by the tunnel"; break;
                    case "Tor HTTP": message = "tor http proxy listening on " + endpoint.address; break;
                    case "Psiphon HTTP": message = "psiphon http proxy on " + endpoint.address; break;
                    // In Chain mode the final SOCKS listener is announced by the same Core
                    // "psiphon is ready" line the side proxy always used; there is no separate
                    // base announcement because the base listener is only the underlay.
                    case "SOCKS5":
                    default:
                        if (torChain) {
                            message = "tor is ready; " + endpoint.address + " leaves through tor, carried by the tunnel";
                        } else {
                            message = topology.equals("reverse") ? "psiphon is ready; the tunnel goes out through " + endpoint.address
                                    : "psiphon is ready; " + endpoint.address + " leaves through psiphon"
                                    + (topology.equals("chain") || chain ? ", carried by the tunnel" : "");
                        }
                }
                expected.put(key(endpoint), message.toLowerCase(Locale.ROOT));
            }
        }

        synchronized void onCoreLog(String line) {
            String message = line.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : expected.entrySet())
                if (message.endsWith(entry.getValue())) ready.add(entry.getKey());
            if (message.contains("psiphon stopped:"))
                ready.removeIf(key -> expected.get(key).startsWith("psiphon"));
            if (message.contains("the tor http proxy stopped:"))
                ready.removeIf(key -> expected.get(key).startsWith("tor http proxy"));
        }

        synchronized boolean announced(Endpoint endpoint) {
            return !endpoint.privacy || ready.contains(key(endpoint));
        }

        synchronized boolean allAnnounced() { return ready.containsAll(expected.keySet()); }

        private static String key(Endpoint endpoint) { return endpoint.name + "@" + endpoint.address; }
    }

    private RuntimeTrafficPlan() { }
}
