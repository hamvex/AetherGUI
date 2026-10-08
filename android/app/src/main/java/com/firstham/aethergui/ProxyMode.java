package com.firstham.aethergui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Fixed local endpoints for the existing, persisted "manual" connection mode. */
final class ProxyMode {
    static final String SOCKS_ADDRESS = "127.0.0.1:1819";
    static final String HTTP_ADDRESS = "127.0.0.1:1818";
    static final String VPN_SOCKS_DEFAULT = "127.0.0.1:1819";
    private static final int HTTP_HEAD_LIMIT = 16 * 1024;

    static boolean enabled(String mode) { return "manual".equals(mode); }

    static String socksAddress(String mode, String savedAddress) {
        return enabled(mode) ? SOCKS_ADDRESS : savedAddress == null ? VPN_SOCKS_DEFAULT : savedAddress;
    }

    /** Override only the effective request; never rewrite the user's VPN settings. */
    static Map<String, Object> settings(String mode, Map<String, ?> saved) {
        Map<String, Object> effective = CoreSettings.values(saved);
        if (enabled(mode)) {
            effective.put("httpProxy", HTTP_ADDRESS);
            // dev.017 CHANGE 5: the internal full-device privacy CHAINS are not offered in Proxy
            // mode (the Protocol selector hides the combined entries), so a chain saved from
            // Device VPN mode is deactivated for the connection only — the base protocol serves
            // the public 1818/1819 endpoints directly (the dev.016 Psiphon-OFF proxy contract).
            // The stored preferences are never rewritten, so returning to Device VPN reactivates
            // the user's previous Psiphon/Tor selections untouched. The "only" topology is left
            // untouched here because it is already capability-blocked before any start.
            if (PsiphonChainRouting.chainActive(effective)) effective.put("psiphonMode", "off");
            if (TorChainRouting.chainActive(effective)) effective.put("torMode", "side");
        }
        return effective;
    }

    /** Bind both ports at once, closing the first even when the second cannot be reserved. */
    static void checkAvailable(String socksAddress, String httpAddress) throws IOException {
        try (ServerSocket socks = reserve("SOCKS5", socksAddress);
             ServerSocket http = reserve("HTTP", httpAddress)) {
            // Core performs the final bind. Its own announcements and protocol replies are
            // required after launch, since another process can bind after this preflight.
        }
    }

    private static ServerSocket reserve(String listener, String address) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            // Match Core's Linux/Android bind semantics: permit TIME_WAIT after disconnect,
            // while a live listener still prevents a second bind (SO_REUSEPORT is not set).
            socket.setReuseAddress(true);
            socket.bind(endpoint(address));
            return socket;
        } catch (IOException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw new PortUnavailableException(listener, address, error);
        } catch (RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    static final class PortUnavailableException extends IOException {
        final String listener;
        final String address;
        PortUnavailableException(String listener, String address, IOException cause) {
            super(listener + " cannot listen on " + address, cause);
            this.listener = listener;
            this.address = address;
        }
    }

    /** One instance per child process; an old reader cannot announce readiness for a new child. */
    static final class Listeners {
        private final boolean psiphonOnly;
        private volatile boolean socks;
        private volatile boolean http;
        private volatile String failedListener;

        Listeners() { this("off"); }

        Listeners(String psiphonTopology) { psiphonOnly = "only".equals(psiphonTopology); }

        void onCoreLog(String line) {
            String message = line.trim();
            if (psiphonOnly) {
                if (message.endsWith("psiphon is ready; " + SOCKS_ADDRESS + " leaves through psiphon")) socks = true;
                if (message.endsWith("psiphon http proxy on " + HTTP_ADDRESS)) http = true;
            } else {
                if (message.endsWith("socks5 server listening on " + SOCKS_ADDRESS)) socks = true;
                if (message.endsWith("http proxy listening on " + HTTP_ADDRESS)) http = true;
            }
            if (message.contains("the socks5 listener cannot use " + SOCKS_ADDRESS + ":")) failedListener = "SOCKS5";
            if (message.contains("the http proxy listener cannot use " + HTTP_ADDRESS + ":")) failedListener = "HTTP";
        }

        boolean announced() { return socks && http; }
        String failedListener() { return failedListener; }
    }

    /** Each required protocol must independently carry traffic before publishing Connected. */
    static final class TrafficProof {
        private final boolean dual;
        private boolean socks;
        private boolean http;
        TrafficProof(boolean dual) { this.dual = dual; }
        boolean record(boolean viaHttp) {
            if (viaHttp) http = true;
            else socks = true;
            return socks && (!dual || http);
        }
    }

    /** A malformed CONNECT must get Core's HTTP 400 without generating upstream traffic. */
    static boolean httpResponds(String address) {
        try (Socket socket = new Socket()) {
            socket.connect(endpoint(address), 250);
            socket.setSoTimeout(400);
            socket.getOutputStream().write("CONNECT\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return status(readHeader(socket), 400);
        } catch (IOException | IllegalArgumentException notReady) {
            return false;
        }
    }

    /** Client-side CONNECT for the same HTTPS traffic gate used by SOCKS; this is not a server. */
    static Socket openHttpTunnel(String address, String host, int port, int timeoutMs) throws IOException {
        return openHttpTunnel(address, host, port, timeoutMs, null);
    }

    static Socket openHttpTunnel(String address, String host, int port, int timeoutMs, ProbeCancellation cancellation) throws IOException {
        if (host == null || host.isEmpty() || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0
                || host.indexOf('\0') >= 0 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid HTTP CONNECT destination");
        }
        Socket socket = new Socket();
        try {
            if (cancellation != null) cancellation.track(socket);
            socket.connect(endpoint(address), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            String authority = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
            socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: "
                    + authority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            if (!status(readHeader(socket), 200)) throw new IOException("HTTP proxy refused CONNECT");
            return socket;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private static String readHeader(Socket socket) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        while (header.size() < HTTP_HEAD_LIMIT) {
            int value = socket.getInputStream().read();
            if (value < 0) throw new IOException("HTTP proxy response ended before its headers");
            header.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return new String(header.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new IOException("HTTP proxy response headers exceeded the limit");
    }

    private static boolean status(String header, int expected) {
        String first = header.substring(0, header.indexOf("\r\n"));
        return first.matches("HTTP/1\\.[01] " + expected + "(?: .*)?");
    }

    private static InetSocketAddress endpoint(String address) {
        if (!CoreSettings.endpoint(address, true)) throw new IllegalArgumentException("Proxy listener must use a loopback IP:port");
        int colon = address.lastIndexOf(':');
        String host = address.substring(0, colon).replace("[", "").replace("]", "");
        return new InetSocketAddress(host, Integer.parseInt(address.substring(colon + 1)));
    }

    private ProxyMode() { }
}
