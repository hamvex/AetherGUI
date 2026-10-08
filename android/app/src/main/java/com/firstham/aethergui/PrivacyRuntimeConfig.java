package com.firstham.aethergui;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PrivacyRuntimeConfig {
    static final String TOR_SOCKS = "127.0.0.1:1821";
    static final String PSIPHON_SOCKS = "127.0.0.1:1822";
    static final String PSIPHON_HTTP = "127.0.0.1:1824";
    static final String TOR_HTTP = "127.0.0.1:1825";

    static Map<String, String> mappedOptions(Map<String, ?> source, String protocol) {
        Map<String, Object> values = CoreSettings.values(source);
        String invalid = PrivacySettings.invalid(values, protocol);
        if (invalid != null) throw new IllegalArgumentException("Invalid privacy setting: " + invalid);
        Map<String, String> environment = new LinkedHashMap<>();
        String mode = CoreSettings.string(values, "psiphonMode");
        boolean chain = PsiphonChainRouting.chainActive(values);
        // dev.034: the v2.3.0 exit-country policy. OFF (the default) emits nothing at all -
        // upstream then never looks up the exit and never refuses a tunnel, so there is no
        // hidden country blacklist. Allow/exclusive maps to the exact Core syntax
        // ("DE,SE" allow-only, "!IR,AZ,RU" exclude), checked through the finished tunnel
        // before SOCKS opens and rechecked every exitLocationSecs; a policy-triggered drop
        // surfaces through the existing reconnect handling, not as an unexplained crash.
        String exitPolicy = PrivacySettings.exitPolicy(values);
        if (!exitPolicy.isEmpty()) {
            if (!PrivacyCapabilities.isAvailable(PrivacyCapabilities.EXIT_LOCATION))
                throw new IllegalArgumentException("Exit-country policy pending runtime validation: exitLocationEnabled");
            environment.put("AETHER_EXIT_LOC", exitPolicy);
            environment.put("AETHER_EXIT_LOC_SECS", CoreSettings.string(values, "exitLocationSecs"));
        }
        // While the final egress is the Psiphon chain, the Core's own HTTP proxy would either
        // collide with the public 1818 contract or be an unused duplicate; the public port is
        // served from the Psiphon HTTP listener instead. The saved httpProxy setting is not
        // rewritten, and non-chain topologies keep the previous behavior exactly.
        String baseHttp = chain ? "" : baseHttpAddress(values);
        if (!baseHttp.isEmpty()) environment.put("AETHER_HTTP_PROXY", baseHttp);
        if (!mode.equals("off")) {
            environment.put("AETHER_PSIPHON", PsiphonConfiguration.coreMode(mode));
            if (!mode.equals("only")) environment.put("AETHER_PSIPHON_BIND", PSIPHON_SOCKS);
            if (PsiphonConfiguration.usesChainTransport(mode))
                environment.put("AETHER_PSIPHON_MODE", CoreSettings.string(values, "psiphonTransport"));
            String region = CoreSettings.string(values, "psiphonRegion");
            if (!region.isEmpty()) environment.put("AETHER_PSIPHON_REGION", region.toUpperCase(Locale.ROOT));
            String psiphonHttp = psiphonHttpAddress(values);
            // Chain is the final device egress, so the HTTP listener it needs to keep the public
            // 1818 contract is an internal implementation detail: it is provisioned automatically
            // and requires no user action, exactly as the full-device design requires. The saved
            // psiphonHttp setting keeps its meaning for the non-chain topologies.
            if (psiphonHttp.isEmpty() && chain) psiphonHttp = PSIPHON_HTTP;
            if (!psiphonHttp.isEmpty()) environment.put("AETHER_PSIPHON_HTTP", psiphonHttp);
        }
        if (CoreSettings.enabled(values, "torProxy")) {
            // dev.017 CHANGE 7: with torMode=chain the official Core runs aether -> warp -> tor
            // -> internet and the Tor SOCKS listener on TOR_SOCKS is the final egress; the base
            // SOCKS listener moves to the internal underlay address exactly like the Psiphon
            // chain. With torMode=side (the dev.016 contract), AETHER_TOR=chain with the Tor bind
            // on 1821 keeps the separate side-proxy behavior unchanged.
            environment.put("AETHER_TOR", "chain");
            environment.put("AETHER_TOR_BIND", TorChainRouting.chainActive(values)
                    ? TOR_SOCKS : "127.0.0.1:1821");
            String policy = CoreSettings.string(values, "torRelayPolicy");
            String count = CoreSettings.string(values, "torRelayCount");
            if (!policy.equals("default")) environment.put("AETHER_TOR_RELAYS", policy.equals("off") ? "off" : (policy.equals("only") ? "only:" : "") + count);
            if (policy.equals("additional") || policy.equals("only")) environment.put("AETHER_TOR_BRIDGES", "auto");
            // A full-device Tor chain is the final device egress, so the HTTP listener it needs to
            // keep the public 1818 contract is an internal implementation detail: it is
            // provisioned automatically and requires no user action, exactly as the Psiphon chain
            // provisions its HTTP listener. The saved torHttp setting keeps its meaning for the
            // side topology.
            if (TorChainRouting.chainActive(values)) {
                environment.put("AETHER_TOR_HTTP", TOR_HTTP);
                environment.put("AETHER_TOR_BRIDGES", "off");
            } else {
                if (CoreSettings.enabled(values, "torHttp")) environment.put("AETHER_TOR_HTTP", TOR_HTTP);
                // Side mode keeps the dev.015/dev.016 "bridges off" default; the policy-specific
                // values above take precedence.
                if (!environment.containsKey("AETHER_TOR_BRIDGES")) environment.put("AETHER_TOR_BRIDGES", "off");
            }
        }
        return environment;
    }

    private static String baseHttpAddress(Map<String, ?> values) {
        return CoreSettings.string(values, "psiphonMode").equals("only") ? "" : CoreSettings.string(values, "httpProxy");
    }

    private static String psiphonHttpAddress(Map<String, ?> values) {
        String mode = CoreSettings.string(values, "psiphonMode");
        if (mode.equals("off")) return "";
        String configured = CoreSettings.string(values, "httpProxy");
        if (mode.equals("only") && !configured.isEmpty()) return configured;
        return CoreSettings.enabled(values, "psiphonHttp") ? PSIPHON_HTTP : "";
    }

    static Map<String, String> listenerAddresses(Map<String, ?> source, String socks) {
        Map<String, Object> values = CoreSettings.values(source);
        Map<String, String> listeners = new LinkedHashMap<>();
        String mode = CoreSettings.string(values, "psiphonMode");
        boolean chain = PsiphonChainRouting.chainActive(values);
        boolean torChain = TorChainRouting.chainActive(values);
        // The key is the listener role, the value its real bind address in the current plan.
        // In Chain mode the plan is canonical and ignores the caller's base address: the final
        // SOCKS listener is the Psiphon proxy itself, the base SOCKS is the internal underlay,
        // and the published 1818/1819 ports are the app's relays in front of the Psiphon
        // listeners. The collision check and the port-release wait run over this map, so every
        // address the plan touches - including both relay ports - is covered.
        if (chain) {
            listeners.put("SOCKS5", PSIPHON_SOCKS);
            listeners.put("Base", PsiphonChainRouting.INTERNAL_CORE_SOCKS);
            listeners.put("HTTP", ProxyMode.HTTP_ADDRESS);
            listeners.put("Public SOCKS5", ProxyMode.SOCKS_ADDRESS);
            String psiphonHttp = psiphonHttpAddress(values);
            listeners.put("Psiphon HTTP", psiphonHttp.isEmpty() ? PSIPHON_HTTP : psiphonHttp);
        } else if (torChain) {
            // dev.017 CHANGE 7: the Tor chain mirrors the Psiphon chain topology exactly. The
            // final SOCKS listener is Tor itself on 1821, the base SOCKS moves to the internal
            // underlay port, and the published 1818/1819 ports become the app's relays in front
            // of the Tor listeners. The Tor HTTP listener is auto-provisioned for the public 1818
            // contract, so it is part of the plan regardless of the saved torHttp setting.
            listeners.put("SOCKS5", TOR_SOCKS);
            listeners.put("Base", TorChainRouting.INTERNAL_CORE_SOCKS);
            listeners.put("HTTP", ProxyMode.HTTP_ADDRESS);
            listeners.put("Public SOCKS5", ProxyMode.SOCKS_ADDRESS);
            listeners.put("Tor HTTP", TOR_HTTP);
        } else {
            listeners.put("SOCKS5", socks);
            String http = baseHttpAddress(values);
            if (!http.isEmpty()) listeners.put("HTTP", http);
            if (!mode.equals("off") && !mode.equals("only")) listeners.put("Psiphon", PSIPHON_SOCKS);
            if (!mode.equals("off")) {
                String psiphonHttp = psiphonHttpAddress(values);
                if (!psiphonHttp.isEmpty()) listeners.put("Psiphon HTTP", psiphonHttp);
            }
            if (CoreSettings.enabled(values, "torProxy")) {
                listeners.put("Tor", "127.0.0.1:1821");
                if (CoreSettings.enabled(values, "torHttp")) listeners.put("Tor HTTP", TOR_HTTP);
            }
        }
        return java.util.Collections.unmodifiableMap(listeners);
    }

    static void validateListeners(Map<String, ?> source, String socks, Integer lanPort) {
        Set<Integer> ports = new HashSet<>();
        for (String address : listenerAddresses(source, socks).values()) addPort(ports, address);
        if (lanPort != null && (lanPort < 1 || lanPort > 65535 || !ports.add(lanPort)))
            throw new IllegalArgumentException("Proxy listener ports must differ");
    }

    private static void addPort(Set<Integer> ports, String address) {
        if (!CoreSettings.endpoint(address, true)) throw new IllegalArgumentException("Invalid local proxy listener");
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        if (!ports.add(port)) throw new IllegalArgumentException("Proxy listener ports must differ");
    }

    private PrivacyRuntimeConfig() { }
}
