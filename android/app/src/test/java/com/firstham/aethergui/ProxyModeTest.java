package com.firstham.aethergui;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class ProxyModeTest {
    @Test public void savedManualModeStillSelectsProxyWithoutChangingVpnPreferences() {
        String mode = VpnConnectionController.normalizedMode("manual");
        assertTrue(ProxyMode.enabled(mode));
        assertEquals("127.0.0.1:1819", ProxyMode.socksAddress(mode, "127.0.0.1:1819"));
        assertEquals("127.0.0.1:1819", ProxyMode.socksAddress(mode, "127.0.0.1:1080"));
        assertEquals("127.0.0.1:1080", ProxyMode.socksAddress("vpn", "127.0.0.1:1080"));
        assertEquals("127.0.0.1:1819", ProxyMode.socksAddress("vpn", null));
        assertEquals("vpn", VpnConnectionController.normalizedMode("smart"));
        assertEquals(3, VpnConnectionController.normalizedProtocolIndex("smart", 0));
    }

    @Test public void proxyAlwaysUsesTheCoreHttpListenerAt1818WithoutMutatingSavedValues() {
        Map<String, Object> saved = new HashMap<>(CoreSettings.DEFAULTS);
        saved.put("httpProxy", "127.0.0.1:1820");
        for (String protocol : new String[]{"wg", "gool", "masque", "mim"}) {
            Map<String, Object> effective = ProxyMode.settings("manual", saved);
            Map<String, String> env = CoreSettings.environment(effective, protocol, "h2");
            assertEquals("127.0.0.1:1818", env.get("AETHER_HTTP_PROXY"));
            assertEquals("127.0.0.1:1820", saved.get("httpProxy"));
        }
    }

    @Test public void vpnCoreConfigurationIsUnchangedIncludingItsOptionalHttpListener() {
        for (String http : new String[]{"", "127.0.0.1:1820"}) {
            Map<String, Object> saved = new HashMap<>(CoreSettings.DEFAULTS);
            saved.put("httpProxy", http);
            for (String protocol : new String[]{"wg", "gool", "masque", "mim"}) {
                assertEquals(CoreSettings.environment(saved, protocol, "h2"),
                        CoreSettings.environment(ProxyMode.settings("vpn", saved), protocol, "h2"));
            }
        }
        assertFalse(CoreSettings.environment(ProxyMode.settings("vpn", CoreSettings.DEFAULTS),
                "gool", "h3").containsKey("AETHER_HTTP_PROXY"));
    }

    @Test public void bothProxyAddressesAreIpv4LoopbackAndHaveDifferentRequiredPorts() {
        assertEquals("127.0.0.1:1819", ProxyMode.SOCKS_ADDRESS);
        assertEquals("127.0.0.1:1818", ProxyMode.HTTP_ADDRESS);
        assertTrue(CoreSettings.endpoint(ProxyMode.SOCKS_ADDRESS, true));
        assertTrue(CoreSettings.endpoint(ProxyMode.HTTP_ADDRESS, true));
        assertFalse(CoreSettings.endpoint("0.0.0.0:1819", true));
        assertFalse(CoreSettings.endpoint("192.168.1.2:1819", true));
    }

    @Test public void occupiedSocksPortIsReportedAndTheOtherListenerIsNotTouched() throws Exception {
        try (ServerSocket occupied = bind(0); ServerSocket other = bind(0)) {
            try {
                ProxyMode.checkAvailable(address(occupied), address(other));
                fail("occupied SOCKS port was accepted");
            } catch (ProxyMode.PortUnavailableException expected) {
                assertEquals("SOCKS5", expected.listener);
                assertEquals(address(occupied), expected.address);
            }
            assertTrue(occupied.isBound());
            assertFalse(occupied.isClosed());
            assertFalse(other.isClosed());
        }
    }

    @Test public void occupiedHttpPortReleasesTheSocksReservationAndPreservesItsOwner() throws Exception {
        String socks;
        try (ServerSocket available = bind(0)) { socks = address(available); }
        try (ServerSocket occupied = bind(0)) {
            try {
                ProxyMode.checkAvailable(socks, address(occupied));
                fail("occupied HTTP port was accepted");
            } catch (ProxyMode.PortUnavailableException expected) {
                assertEquals("HTTP", expected.listener);
                assertEquals(address(occupied), expected.address);
            }
            try (ServerSocket rebound = bind(port(socks))) { assertTrue(rebound.isBound()); }
            try (Socket client = new Socket("127.0.0.1", occupied.getLocalPort());
                 Socket accepted = occupied.accept()) {
                assertTrue(accepted.isConnected());
            }
        }
    }

    @Test public void successfulPreflightReleasesBothPortsForCoreAndCanBeRepeated() throws Exception {
        String socks, http;
        try (ServerSocket one = bind(0); ServerSocket two = bind(0)) {
            socks = address(one); http = address(two);
        }
        for (int cycle = 0; cycle < 3; cycle++) {
            ProxyMode.checkAvailable(socks, http);
            try (ServerSocket one = bind(port(socks)); ServerSocket two = bind(port(http))) {
                assertTrue(one.isBound()); assertTrue(two.isBound());
            }
        }
    }

    @Test public void publicHttpAddressIsRefusedAndAnyFirstReservationIsClosed() throws Exception {
        String socks;
        try (ServerSocket one = bind(0)) { socks = address(one); }
        try {
            ProxyMode.checkAvailable(socks, "0.0.0.0:1819");
            fail("public listener allowed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("loopback"));
        }
        try (ServerSocket rebound = bind(port(socks))) { assertTrue(rebound.isBound()); }
    }

    @Test public void readinessBelongsToOneCoreAndBothExactAddressesMustBeAnnounced() {
        ProxyMode.Listeners first = new ProxyMode.Listeners();
        // Exact messages from the official v2.0.0 socks.rs serve/serve_http functions.
        first.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        first.onCoreLog("[+] http proxy listening on 127.0.0.1:18180");
        first.onCoreLog("[+] http proxy listening on 0.0.0.0:1818");
        assertFalse(first.announced());
        first.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertTrue(first.announced());
        ProxyMode.Listeners restarted = new ProxyMode.Listeners();
        restarted.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertFalse(restarted.announced());
        first.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        assertFalse(restarted.announced());
    }

    @Test public void onlyReadinessRequiresBothOfficialPsiphonAnnouncements() {
        ProxyMode.Listeners only = new ProxyMode.Listeners("only");
        only.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        only.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertFalse(only.announced());
        only.onCoreLog("[+] psiphon is ready; 127.0.0.1:1819 leaves through psiphon, carried by the tunnel");
        only.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1818");
        assertFalse(only.announced());
        only.onCoreLog("[+] psiphon is ready; 127.0.0.1:18190 leaves through psiphon");
        only.onCoreLog("[+] psiphon is ready; 0.0.0.0:1819 leaves through psiphon");
        assertFalse(only.announced());
        only.onCoreLog("[+] psiphon is ready; 127.0.0.1:1819 leaves through psiphon");
        assertTrue(only.announced());
        ProxyMode.Listeners restarted = new ProxyMode.Listeners("only");
        restarted.onCoreLog("[+] psiphon is ready; 127.0.0.1:1819 leaves through psiphon");
        restarted.onCoreLog("[+] psiphon http proxy on 127.0.0.1:18180");
        restarted.onCoreLog("[+] psiphon http proxy on 0.0.0.0:1818");
        restarted.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertFalse(restarted.announced());
        only.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1818");
        assertFalse(restarted.announced());
        restarted.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1818");
        assertTrue(restarted.announced());
    }

    @Test public void chainStillRequiresTheBaseCoreDualListeners() {
        ProxyMode.Listeners chain = new ProxyMode.Listeners("chain");
        chain.onCoreLog("[+] psiphon is ready; 127.0.0.1:1819 leaves through psiphon");
        chain.onCoreLog("[+] psiphon http proxy on 127.0.0.1:1818");
        assertFalse(chain.announced());
        chain.onCoreLog("[+] socks5 server listening on 127.0.0.1:1819");
        assertFalse(chain.announced());
        chain.onCoreLog("[+] http proxy listening on 127.0.0.1:1818");
        assertTrue(chain.announced());
    }

    @Test public void aBindFailureAfterPreflightRetainsTheConflictingProtocol() {
        ProxyMode.Listeners listeners = new ProxyMode.Listeners();
        listeners.onCoreLog("the http proxy listener cannot use 127.0.0.1:1818: address already in use");
        assertEquals("HTTP", listeners.failedListener());
        assertFalse(listeners.announced());
    }

    @Test public void eachProxyProtocolMustIndependentlyProveTraffic() {
        ProxyMode.TrafficProof dual = new ProxyMode.TrafficProof(true);
        assertFalse(dual.record(false));
        assertFalse(dual.record(false));
        assertTrue(dual.record(true));
        dual = new ProxyMode.TrafficProof(true);
        assertFalse(dual.record(true));
        assertTrue(dual.record(false));
        ProxyMode.TrafficProof vpn = new ProxyMode.TrafficProof(false);
        assertFalse(vpn.record(true));
        assertTrue(vpn.record(false));
    }

    @Test public void httpReadinessRequiresACoreProtocolResponse() throws Exception {
        try (ReplyProxy proxy = new ReplyProxy("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n", false)) {
            assertTrue(ProxyMode.httpResponds(proxy.address()));
            proxy.finished();
            assertEquals("CONNECT\r\n\r\n", proxy.request);
        }
        try (ReplyProxy proxy = new ReplyProxy("unrelated service\r\n\r\n", false)) {
            assertFalse(ProxyMode.httpResponds(proxy.address()));
        }
        try (ReplyProxy proxy = new ReplyProxy("", false)) {
            assertFalse(ProxyMode.httpResponds(proxy.address()));
        }
    }

    @Test public void httpConnectHandlesFragmentedHeadersAndPreservesTunnelBytes() throws Exception {
        try (ReplyProxy proxy = new ReplyProxy("HTTP/1.1 200 Connection established\r\nX-Test: yes\r\n\r\npayload", true);
             Socket tunnel = ProxyMode.openHttpTunnel(proxy.address(), "example.com", 443, 1500)) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] bytes = new byte[32];
            int count;
            while ((count = tunnel.getInputStream().read(bytes)) != -1) body.write(bytes, 0, count);
            assertEquals("payload", new String(body.toByteArray(), StandardCharsets.US_ASCII));
            proxy.finished();
            assertEquals("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n", proxy.request);
        }
    }

    @Test public void failedOrMalformedHttpConnectNeverCountsAsAWorkingTunnel() throws Exception {
        for (String response : new String[]{
                "HTTP/1.1 502 Bad Gateway\r\n\r\n", "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n",
                "HTTP/1.1 2000 Bogus\r\n\r\n", "HTTP/1.1 200 OK\r\n", "SOCKS5\r\n\r\n"}) {
            try (ReplyProxy proxy = new ReplyProxy(response, false)) {
                try (Socket unexpected = ProxyMode.openHttpTunnel(proxy.address(), "example.com", 443, 1500)) {
                    fail("accepted " + response);
                } catch (IOException expected) { assertNotNull(expected.getMessage()); }
            }
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        return socket;
    }
    private static String address(ServerSocket server) { return "127.0.0.1:" + server.getLocalPort(); }
    private static int port(String address) { return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)); }

    private static final class ReplyProxy implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Future<?> done;
        volatile String request;
        ReplyProxy(String reply, boolean fragment) throws IOException {
            server = bind(0);
            done = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int tail = 0;
                    while (header.size() < 16384) {
                        int value = socket.getInputStream().read();
                        if (value < 0) throw new IOException("request ended early");
                        header.write(value); tail = (tail << 8) | value;
                        if (tail == 0x0d0a0d0a) break;
                    }
                    request = new String(header.toByteArray(), StandardCharsets.US_ASCII);
                    byte[] data = reply.getBytes(StandardCharsets.US_ASCII);
                    if (fragment) for (byte value : data) { socket.getOutputStream().write(value); socket.getOutputStream().flush(); }
                    else { socket.getOutputStream().write(data); socket.getOutputStream().flush(); }
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        }
        String address() { return ProxyModeTest.address(server); }
        void finished() throws Exception { done.get(3, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            try { finished(); } finally { server.close(); worker.shutdownNow(); }
        }
    }
}
