package com.firstham.aethergui;

import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The Android contract for the pinned Aether 2.3.0 CLI. No network or Android calls. */
final class CoreSettings {
    static final Map<String, Object> DEFAULTS;
    static {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ech", ""); d.put("h2Fragment", false);
        // v2.3.0 upstream default (fragment.rs parse_range default): size 8-16, delay 2-10.
        // A stored value is always the user's explicit choice and is never migrated.
        // The single exception is a stored value equal to the v2.1.0 default 16-32, moved
        // to the new upstream default exactly once by AndroidCoreSettings (marker below).
        d.put("h2FragmentSize", "8-16"); d.put("h2FragmentDelay", "2-10");
        // dev.034: one-time marker that the v2.1.0 -> v2.3.0 fragment-size default change
        // has been applied; without it a later explicit 16-32 choice would be rewritten.
        d.put("fragmentSizeV230", false);
        d.put("h2FragmentSni", false);
        d.put("noDataCheck", false); d.put("validateSecs", 10); d.put("startupSecs", 30); d.put("reconnectSecs", 2);
        d.put("wiwOuterPeer", ""); d.put("wiwInnerPeer", ""); d.put("mimOuterPeer", ""); d.put("mimInnerPeer", "");
        // dev.034: the two v2.3.0 Gool topologies. "masque" is the new-Core Gool over MASQUE
        // (the fresh default); "classic" is the legacy WARP-in-WARP Gool. Migration preserves
        // an existing Gool user's legacy semantics by writing "classic" (AndroidCoreSettings).
        d.put("goolMode", "masque"); d.put("goolInnerPeer", "");
        d.put("apiFragment", false);
        d.put("team", ""); d.put("accessEmail", ""); d.put("accessToken", "");
        d.put("accessClientId", ""); d.put("accessClientSecret", ""); d.put("gateway", false);
        d.put("upstreamProxy", ""); d.put("wgNoProfileRetry", false); d.put("wgKeepalive", 5);
        d.put("routeSniff", true); d.put("routeSniffMs", 400); d.put("routeBlock", ""); d.put("routeDirect", "");
        d.put("quicV2", true); d.put("h2Peer", ""); d.put("dns", ""); d.put("httpProxy", "");
        d.put("torProxy", false);
        // dev.034 v2.3.0 surface: ECH Auto lookup knobs, TLS fingerprint controls, identity
        // controls, and expert reliability budgets. Defaults follow the exact upstream source.
        d.put("echDns", ""); d.put("echDomain", "");
        d.put("tlsCiphers", ""); d.put("tlsGroups", ""); d.put("grease", true); d.put("tlsVerify", false);
        d.put("enrollAddress", ""); d.put("reprovision", true);
        d.put("perfProfile", ""); d.put("statsLogging", false);
        d.put("tcpConnectSecs", 30); d.put("h2KeepaliveSecs", 15); d.put("h2KeepaliveTimeoutSecs", 20);
        d.put("wgEndpointCooldownSecs", 300); d.put("wgStaleSecs", 10);
        d.putAll(PrivacySettings.DEFAULTS);
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    static Map<String, Object> values(Map<String, ?> source) {
        Map<String, Object> values = new LinkedHashMap<>(DEFAULTS);
        for (String key : DEFAULTS.keySet()) if (source.containsKey(key)) values.put(key, source.get(key));
        return values;
    }

    static boolean masque(String protocol) { return "masque".equals(protocol) || "mim".equals(protocol); }
    /** The v2.3.0 Gool over MASQUE topology: protocol gool with goolMode not classic. */
    static boolean goolOverMasque(String protocol, Map<String, ?> v) {
        return "gool".equals(protocol) && !"classic".equals(string(v, "goolMode"));
    }
    /** Any session whose carrier is a MASQUE tunnel: masque, mim, or Gool over MASQUE. */
    static boolean masqueCarrier(String protocol, Map<String, ?> v) {
        return masque(protocol) || goolOverMasque(protocol, v);
    }
    static String string(Map<String, ?> v, String key) { Object value = v.get(key); return value == null ? "" : value.toString().trim(); }
    static boolean enabled(Map<String, ?> v, String key) { return Boolean.TRUE.equals(v.get(key)); }

    /** Returns a field key, never a credential or its value. Android displays a localized error. */
    static String invalid(Map<String, ?> source, String protocol, String transport) {
        Map<String, Object> v = values(source);
        for (Map.Entry<String, Object> e : DEFAULTS.entrySet()) {
            Object actual = v.get(e.getKey());
            if (actual == null || !e.getValue().getClass().isInstance(actual)) return e.getKey();
            String s = string(v, e.getKey());
            if (s.length() > 8192 || s.indexOf('\0') >= 0 || s.indexOf('\r') >= 0) return e.getKey();
            if (!e.getKey().startsWith("route") && s.indexOf('\n') >= 0) return e.getKey();
        }
        for (String key : new String[]{"validateSecs", "startupSecs", "reconnectSecs", "routeSniffMs", "wgKeepalive",
                "tcpConnectSecs", "h2KeepaliveSecs", "h2KeepaliveTimeoutSecs", "wgEndpointCooldownSecs", "wgStaleSecs",
                "exitLocationSecs"}) {
            int n = (Integer)v.get(key);
            int max = key.equals("routeSniffMs") ? 5000 : key.equals("wgKeepalive") ? 120 : key.equals("reconnectSecs") ? 30
                    : key.equals("validateSecs") || key.equals("startupSecs") ? 60 : 86400;
            if (n < 1 || n > max) return key;
        }
        if (!range(string(v, "h2FragmentSize"), 1, 16384)) return "h2FragmentSize";
        if (!range(string(v, "h2FragmentDelay"), 0, 100)) return "h2FragmentDelay";
        String ech = string(v, "ech");
        if (!ech.isEmpty() && !ech.equals("auto")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(ech);
                // ECHConfigList has a two-byte length followed by one or more encoded configs.
                if (bytes.length < 6 || (((bytes[0] & 255) << 8) | (bytes[1] & 255)) != bytes.length - 2) return "ech";
            } catch (IllegalArgumentException invalid) { return "ech"; }
        }
        for (String key : new String[]{"wiwOuterPeer", "wiwInnerPeer", "mimOuterPeer", "mimInnerPeer", "h2Peer"}) {
            if (!string(v, key).isEmpty() && !endpoint(string(v, key), false)) return key;
        }
        // dev.034: the Gool Mode contract and the Gool over MASQUE inner endpoint.
        String goolMode = string(v, "goolMode");
        if (!goolMode.equals("masque") && !goolMode.equals("classic")) return "goolMode";
        if (!string(v, "goolInnerPeer").isEmpty() && !endpoint(string(v, "goolInnerPeer"), false)) return "goolInnerPeer";
        // dev.034: v2.3.0 ECH Auto lookup fields. The URL forms are exactly the Core's
        // (--ech-dns): udp://ip[:port], tcp://ip[:port], or https://host/path with optional
        // @address= / @sni= parameters; the domain is a plain DNS name.
        String echDns = string(v, "echDns");
        if (!echDns.isEmpty() && !echDnsUrl(echDns)) return "echDns";
        String echDomain = string(v, "echDomain");
        if (!echDomain.isEmpty() && !domainText(echDomain)) return "echDomain";
        // dev.034: TLS fingerprint fields. The Core validates the exact BoringSSL syntax at
        // startup (check_tls_options) and stops with the option named; this pre-start check
        // only rejects characters that can never appear in a cipher list or group list.
        for (String key : new String[]{"tlsCiphers", "tlsGroups"})
            if (!string(v, key).isEmpty() && !string(v, key).matches("[A-Za-z0-9_.:+!,\\-]+(\\s*,\\s*[A-Za-z0-9_.:+!,\\-]+)*")) return key;
        // dev.034: the WARP API enrollment address (ip|name[:port], IPv6 in brackets).
        if (!string(v, "enrollAddress").isEmpty() && !enrollAddress(string(v, "enrollAddress"))) return "enrollAddress";
        String perfProfile = string(v, "perfProfile");
        if (!perfProfile.isEmpty() && !perfProfile.matches("low|medium|high")) return "perfProfile";
        for (String prefix : new String[]{"wiw", "mim"}) {
            String outer = string(v, prefix + "OuterPeer"), inner = string(v, prefix + "InnerPeer");
            if (!outer.isEmpty() && !inner.isEmpty() && host(outer).equals(host(inner))) return prefix + "InnerPeer";
        }
        String team = string(v, "team");
        if (!team.isEmpty() && !team.matches("[A-Za-z0-9_-]{1,63}")) return "team";
        boolean id = !string(v, "accessClientId").isEmpty(), secret = !string(v, "accessClientSecret").isEmpty();
        if (id != secret) return id ? "accessClientSecret" : "accessClientId";
        if (team.isEmpty() && (id || !string(v, "accessToken").isEmpty() || enabled(v, "gateway"))) return "team";
        // Email OTP waits for stdin in upstream; this service has no interactive terminal.
        if (!team.isEmpty() && !id && string(v, "accessToken").isEmpty()) return "accessToken";
        if (!string(v, "upstreamProxy").isEmpty() && !upstream(string(v, "upstreamProxy"))) return "upstreamProxy";
        if (!string(v, "dns").isEmpty()) for (String ip : string(v, "dns").split(",", -1)) if (!ip(ip.trim())) return "dns";
        if (!string(v, "httpProxy").isEmpty() && !endpoint(string(v, "httpProxy"), true)) return "httpProxy";
        for (String key : new String[]{"routeBlock", "routeDirect"}) if (!rules(string(v, key))) return key;
        return PrivacySettings.invalid(v, protocol);
    }

    static void clearInheritedEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.startsWith("AETHER_") || key.equals("RUST_LOG")
                || key.equals("SSL_CERT_FILE") || key.equals("SSL_CERT_DIR"));
    }

    static Map<String, String> environment(Map<String, ?> source, String protocol, String transport) {
        Map<String, Object> v = values(source);
        String invalid = invalid(v, protocol, transport);
        if (invalid != null) throw new IllegalArgumentException("Invalid Core setting: " + invalid);
        String unavailable = PrivacySettings.unavailable(v);
        if (unavailable != null) throw new IllegalArgumentException("Core feature pending runtime validation: " + unavailable);
        Map<String, String> env = new LinkedHashMap<>();
        boolean carrier = masqueCarrier(protocol, v);
        if (carrier) {
            put(env, v, "ech", "AETHER_ECH");
            // v2.3.0: --ech-dns / --ech-domain configure the Auto lookup (they are inert
            // without AETHER_ECH=auto, exactly as upstream reads them).
            put(env, v, "echDns", "AETHER_ECH_DNS");
            put(env, v, "echDomain", "AETHER_ECH_DOMAIN");
            env.put("AETHER_QUIC_V2", enabled(v, "quicV2") ? "1" : "0");
            env.put("AETHER_MASQUE_STARTUP_SECS", string(v, "startupSecs"));
            if ("h2".equals(transport)) {
                put(env, v, "h2Peer", "AETHER_MASQUE_H2_PEER");
                env.put("AETHER_MASQUE_H2_FRAGMENT", enabled(v, "h2Fragment") ? "1" : "0");
                if (enabled(v, "h2Fragment")) {
                    put(env, v, "h2FragmentSize", "AETHER_MASQUE_H2_FRAGMENT_SIZE");
                    put(env, v, "h2FragmentDelay", "AETHER_MASQUE_H2_FRAGMENT_DELAY");
                    // v2.3.0 AETHER_MASQUE_H2_FRAGMENT_SNI: truthy enables the SNI split.
                    if (enabled(v, "h2FragmentSni")) env.put("AETHER_MASQUE_H2_FRAGMENT_SNI", "1");
                }
            }
        }
        if (masque(protocol) || goolOverMasque(protocol, v)) {
            if (enabled(v, "apiFragment")) env.put("AETHER_API_FRAGMENT", "1");
        }
        if (goolOverMasque(protocol, v)) put(env, v, "goolInnerPeer", "AETHER_GOOL_INNER");
        // dev.034: TLS fingerprint controls apply to the H2/H3 handshakes and the WARP API
        // calls for every protocol (upstream Fingerprint::configured is protocol-independent).
        put(env, v, "tlsCiphers", "AETHER_TLS_CIPHERS");
        put(env, v, "tlsGroups", "AETHER_TLS_GROUPS");
        if (!enabled(v, "grease")) env.put("AETHER_DISABLE_GREASE", "1");
        if (enabled(v, "tlsVerify")) env.put("AETHER_TLS_VERIFY", "1");
        put(env, v, "enrollAddress", "AETHER_ENROLL_ADDRESS");
        if (!enabled(v, "reprovision")) env.put("AETHER_REPROVISION", "0");
        put(env, v, "perfProfile", "AETHER_PERF_PROFILE");
        if (enabled(v, "statsLogging")) env.put("AETHER_STATS", "1");
        env.put("AETHER_TCP_CONNECT_SECS", string(v, "tcpConnectSecs"));
        env.put("AETHER_MASQUE_H2_KEEPALIVE_SECS", string(v, "h2KeepaliveSecs"));
        env.put("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS", string(v, "h2KeepaliveTimeoutSecs"));
        for (String prefix : new String[]{"AETHER_MASQUE_", "AETHER_WG_"}) {
            // Upstream checks presence, not truthiness: even "0" disables validation.
            if (enabled(v, "noDataCheck")) env.put(prefix + "NO_DATA_CHECK", "1");
            env.put(prefix + "VALIDATE_SECS", string(v, "validateSecs"));
            env.put(prefix + "RECONNECT_SECS", string(v, "reconnectSecs"));
        }
        if ("gool".equals(protocol) || "mim".equals(protocol)) {
            // Classic Gool only: naming a WiW endpoint selects the classic topology in the
            // Core (gool_classic()), so the endpoint fields are never mapped while Gool over
            // MASQUE is the selected mode - a strict manual selection stays strict.
            if ("gool".equals(protocol) && !goolOverMasque(protocol, v)) {
                env.put("AETHER_GOOL_MODE", "classic");
                // goolInnerPeer is deliberately not mapped in classic mode: an empty
                // env name would leak the value instead of withholding it.
                put(env, v, "wiwOuterPeer", "AETHER_WIW_OUTER_PEER");
                put(env, v, "wiwInnerPeer", "AETHER_WIW_INNER_PEER");
            } else if ("mim".equals(protocol)) {
                put(env, v, "mimOuterPeer", "AETHER_MIM_OUTER_PEER");
                put(env, v, "mimInnerPeer", "AETHER_MIM_INNER_PEER");
            }
        }
        if (!string(v, "team").isEmpty()) {
            put(env, v, "team", "AETHER_TEAM"); put(env, v, "accessToken", "AETHER_ACCESS_TOKEN");
            put(env, v, "accessClientId", "AETHER_ACCESS_CLIENT_ID"); put(env, v, "accessClientSecret", "AETHER_ACCESS_CLIENT_SECRET");
            if (enabled(v, "gateway")) env.put("AETHER_GATEWAY", "1");
        }
        put(env, v, "upstreamProxy", "AETHER_UPSTREAM"); put(env, v, "dns", "AETHER_DNS");
        if ("wg".equals(protocol) || "gool".equals(protocol)) {
            if (enabled(v, "wgNoProfileRetry")) env.put("AETHER_WG_NO_PROFILE_RETRY", "1");
            env.put("AETHER_WG_KEEPALIVE", string(v, "wgKeepalive"));
            env.put("AETHER_WG_ENDPOINT_COOLDOWN_SECS", string(v, "wgEndpointCooldownSecs"));
            env.put("AETHER_WG_STALE_SECS", string(v, "wgStaleSecs"));
        }
        env.put("AETHER_ROUTE_SNIFF", enabled(v, "routeSniff") ? "1" : "0");
        env.put("AETHER_ROUTE_SNIFF_MS", string(v, "routeSniffMs"));
        put(env, v, "routeBlock", "AETHER_ROUTE_BLOCK"); put(env, v, "routeDirect", "AETHER_ROUTE_DIRECT");
        env.putAll(PrivacyRuntimeConfig.mappedOptions(v, protocol));
        return env;
    }

    private static void put(Map<String, String> env, Map<String, ?> v, String key, String name) {
        String s = string(v, key); if (!s.isEmpty()) env.put(name, s);
    }
    static boolean range(String s, int min, int max) {
        if (!s.matches("[0-9]+(-[0-9]+)?")) return false;
        try { String[] p = s.split("-"); int lo = Integer.parseInt(p[0]), hi = Integer.parseInt(p[p.length - 1]); return lo >= min && hi >= lo && hi <= max; }
        catch (NumberFormatException e) { return false; }
    }
    static boolean ip(String s) {
        if (s.contains(":")) {
            if (!s.matches("[0-9a-fA-F:.]+")) return false;
            try { return InetAddress.getByName(s).getAddress().length == 16; } catch (Exception e) { return false; }
        }
        if (!s.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        for (String n : s.split("\\.")) try { if (n.length() > 3 || Integer.parseInt(n) > 255) return false; } catch (Exception e) { return false; }
        return true;
    }
    private static String host(String endpoint) {
        String host = endpoint.substring(0, endpoint.lastIndexOf(':')).replace("[", "").replace("]", "");
        try { return InetAddress.getByName(host).getHostAddress(); } catch (Exception e) { return host; }
    }
    static boolean endpoint(String s, boolean loopback) {
        int colon = s.lastIndexOf(':'); if (colon < 1 || !range(s.substring(colon + 1), 1, 65535) || s.substring(colon + 1).contains("-")) return false;
        String host = s.substring(0, colon);
        if (host.contains(":")) { if (!host.startsWith("[") || !host.endsWith("]")) return false; host = host.substring(1, host.length() - 1); }
        if (!ip(host)) return false;
        return !loopback || host.equals("127.0.0.1") || host.equals("::1");
    }
    static boolean upstream(String s) {
        try {
            URI uri = new URI(s);
            // Upstream implements HTTP CONNECT without TLS, even for https:// (verified
            // unchanged in the exact v2.3.0 tag source, upstream.rs http_connect).
            if (!"socks5".equals(uri.getScheme()) && !"socks5h".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return false;
            return uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPath().isEmpty() || uri.getPath().equals("/"));
        } catch (Exception e) { return false; }
    }
    /** The exact --ech-dns contract of the pinned v2.3.0 source (dns.rs EchDns::parse):
     *  udp://ip[:port] or tcp://ip[:port] with the IP in IPv4, bare IPv6 or bracketed IPv6
     *  form and trailing slashes ignored (a domain is NOT a resolver address), or an
     *  https:// URL whose host is non-empty, optionally followed by @address= (IP or
     *  domain) and @sni= (domain) parameters, each at most once and in either order.
     *  Schemes match case-insensitively exactly as upstream parses them. */
    static boolean echDnsUrl(String s) {
        String value = s.trim();
        if (value.isEmpty() || value.length() > 512) return false;
        if (schemePrefix(value, "https://") > 0) return dohEndpointValid(value);
        for (String scheme : new String[]{"udp://", "tcp://"}) {
            int schemeEnd = schemePrefix(value, scheme);
            if (schemeEnd > 0) {
                String rest = value.substring(schemeEnd).replaceAll("/+$", "");
                return socketAddressText(rest);
            }
        }
        return false;
    }

    /** scheme.length() when value starts with the scheme ignoring ASCII case, else -1. */
    private static int schemePrefix(String value, String scheme) {
        return value.length() >= scheme.length() && value.substring(0, scheme.length()).equalsIgnoreCase(scheme)
                ? scheme.length() : -1;
    }

    /** DohEndpoint::parse: the https:// URL host (up to the first '/', '?' or '#') must be
     *  non-empty; each following @piece must be name=value with the name address or sni. */
    private static boolean dohEndpointValid(String value) {
        int parameter = value.indexOf('@');
        String url = (parameter < 0 ? value : value.substring(0, parameter)).trim();
        if (schemePrefix(url, "https://") < 0) return false;
        String rest = url.substring("https://".length());
        int cut = -1;
        for (char separator : new char[]{'/', '?', '#'}) {
            int at = rest.indexOf(separator);
            if (at >= 0 && (cut < 0 || at < cut)) cut = at;
        }
        if ((cut < 0 ? rest : rest.substring(0, cut)).isEmpty()) return false;
        if (parameter < 0) return true;
        boolean address = false, sni = false;
        for (String piece : value.substring(parameter + 1).split("@", -1)) {
            int equals = piece.indexOf('=');
            if (equals < 0) return false;
            String name = piece.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String setting = piece.substring(equals + 1).trim();
            if (name.equals("address")) {
                if (address || !hostAddressText(setting)) return false;
                address = true;
            } else if (name.equals("sni")) {
                if (sni || looksLikeIp(setting) || !domainText(setting)) return false;
                sni = true;
            } else return false;
        }
        return true;
    }

    /** socket_address(): ip:port, [ipv6]:port, a bare IP, or [ipv6]; domains are refused. */
    private static boolean socketAddressText(String rest) {
        if (rest.isEmpty()) return false;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0 || !ip(rest.substring(1, close))) return false;
            String tail = rest.substring(close + 1);
            return tail.isEmpty() || (tail.startsWith(":") && portText(tail.substring(1)));
        }
        if (rest.indexOf(':') != rest.lastIndexOf(':')) return ip(rest); // bare IPv6 literal only
        int colon = rest.lastIndexOf(':');
        if (colon < 0) return ip(rest);
        return ip(rest.substring(0, colon)) && portText(rest.substring(colon + 1));
    }

    /** host_address(): an IP (brackets stripped) or a domain. */
    private static boolean hostAddressText(String s) {
        return looksLikeIp(s) || domainText(s);
    }

    private static boolean looksLikeIp(String s) {
        String bare = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
        return ip(bare);
    }

    private static boolean portText(String port) {
        return port.matches("[0-9]{1,5}") && Long.parseLong(port) <= 65535;
    }

    /** valid_domain(): labels of letters, digits, '-' and '_' (1-63 bytes each, 253 total),
     *  a trailing dot allowed, single-label names included. */
    static boolean domainText(String name) {
        String trimmed = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isEmpty() || trimmed.length() > 253) return false;
        for (String label : trimmed.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[A-Za-z0-9_-]+")) return false;
        }
        return true;
    }

    /** The --enroll-address contract: an IPv4/IPv6 address or a host name, optionally with a
     *  :port (an IPv6 address then in brackets, e.g. [2606:4700::1]:8443). */
    static boolean enrollAddress(String s) {
        String value = s.trim();
        if (value.isEmpty() || value.length() > 253) return false;
        String host = value;
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') != value.lastIndexOf(':')) {
            // Multiple colons: must be the bracketed [v6]:port or bare v6 form.
            if (value.startsWith("[") && value.indexOf(']') > 0) {
                int close = value.indexOf(']');
                host = value.substring(1, close);
                if (value.length() > close + 1 && value.charAt(close + 1) == ':'
                        && !range(value.substring(close + 2), 1, 65535)) return false;
            } else {
                host = value;
            }
            return host.contains(":") && value.matches("\\[[0-9a-fA-F:]+\\](:[0-9]+)?|[0-9a-fA-F:]+");
        }
        if (colon >= 0) {
            host = value.substring(0, colon);
            if (!range(value.substring(colon + 1), 1, 65535)) return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return ip(host) || host.matches("(?i)([a-z0-9_-]+\\.)+[a-z0-9_-]+");
    }
    static boolean rules(String s) {
        for (String entry : s.split("[,;\n]")) {
            String r = entry.trim(); if (r.isEmpty() || r.startsWith("#") || r.equals("private")) continue;
            if (r.startsWith("port:")) { if (!range(r.substring(5), 1, 65535)) return false; continue; }
            boolean explicitIp = r.startsWith("ip:") || r.startsWith("cidr:");
            if (explicitIp) r = r.substring(r.indexOf(':') + 1);
            if (r.contains("/")) {
                String[] p = r.split("/", -1); if (p.length != 2 || !ip(p[0]) || (p[0].contains(":") && !explicitIp) || !range(p[1], 0, p[0].contains(":") ? 128 : 32) || p[1].contains("-")) return false;
                continue;
            }
            if (ip(r)) { if (r.contains(":") && !explicitIp) return false; continue; }
            if (explicitIp) return false;
            if (r.startsWith("full:") || r.startsWith("domain:") || r.startsWith("suffix:")) r = r.substring(r.indexOf(':') + 1);
            else if (r.startsWith("keyword:")) { if (r.substring(8).isEmpty()) return false; else continue; }
            if (!r.matches("(?i)(\\*\\.)?[a-z0-9_-]+(\\.[a-z0-9_-]+)*\\.?")) return false;
        }
        return true;
    }
    static String redact(String line, Map<String, ?> values) {
        for (String key : new String[]{"accessEmail", "accessToken", "accessClientId", "accessClientSecret", "torBridgeUri"}) {
            String secret = string(values, key); if (!secret.isEmpty()) line = line.replace(secret, "[redacted]");
        }
        try {
            URI uri = new URI(string(values, "upstreamProxy"));
            if (uri.getRawUserInfo() != null) line = line.replace(uri.getRawUserInfo(), "[redacted]");
            if (uri.getUserInfo() != null) for (String secret : uri.getUserInfo().split(":")) if (!secret.isEmpty()) line = line.replace(secret, "[redacted]");
        } catch (Exception ignored) { }
        return line.replaceAll("(?i)(https?://|socks5h?://)[^\\s/@]+@", "$1[redacted]@");
    }
    private CoreSettings() { }
}
