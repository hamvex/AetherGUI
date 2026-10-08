package com.firstham.aethergui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the published Aethon proxy contract (HTTP 127.0.0.1:1818, SOCKS5 127.0.0.1:1819) while the
 * final egress is the Psiphon chain. The Core binds its base listener on the internal port in
 * Chain mode, so these two public ports are owned by the app itself and forward every accepted
 * connection, byte for byte, to the Psiphon SOCKS/HTTP listeners.
 *
 * <p>A plain TCP pipe is protocol-correct here on both ports: a SOCKS5 client speaking to 1819
 * performs its handshake against the Psiphon SOCKS server through the pipe, and an HTTP client
 * speaking to 1818 does the same against the Psiphon HTTP listener. The relay interprets nothing,
 * so CONNECT, plain-HTTP and TLS traffic all pass unchanged. The upstream connection is opened
 * after the client connects, which is what keeps failed connects cheap: if the Psiphon listener
 * is not up yet the pipe fails fast and the client sees a refused connection instead of a hang.
 *
 * <p>Lifecycle: started after the Psiphon listeners are announced ready and stopped on every
 * teardown path (stop, failure, reconnect) together with the rest of the runtime; the executor
 * is shut down with a grace period and remaining server sockets are closed so a reconnect can
 * rebind the public ports deterministically.
 */
final class PublicPortRelays implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int PIPE_BUFFER = 16 * 1024;
    private static final long STOP_GRACE_MS = 1_500L;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<ServerSocket> servers = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    static PublicPortRelays start(String publicSocks, String publicHttp, String psiphonSocks, String psiphonHttp) throws IOException {
        PublicPortRelays relays = new PublicPortRelays();
        try {
            relays.bind(publicSocks, psiphonSocks);
            relays.bind(publicHttp, psiphonHttp);
            return relays;
        } catch (IOException | RuntimeException error) {
            relays.close();
            throw error;
        }
    }

    private void bind(String publicAddress, String upstreamAddress) throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            // Same semantics as ProxyMode's preflight: TIME_WAIT may be reused, a live listener
            // may not be replaced. A stale relay from a torn-down session is closed in stop, so a
            // failure here means an unrelated process owns the published port and must be reported.
            server.setReuseAddress(true);
            server.bind(endpoint(publicAddress));
        } catch (IOException error) {
            try { server.close(); } catch (IOException ignored) { }
            throw new IOException("The public proxy port " + publicAddress + " cannot be relayed", error);
        }
        synchronized (servers) { servers.add(server); }
        executor.execute(() -> accept(server, upstreamAddress));
    }

    private void accept(ServerSocket server, String upstreamAddress) {
        while (!closed.get() && !server.isClosed()) {
            try {
                Socket client = server.accept();
                executor.execute(() -> pipe(client, upstreamAddress));
            } catch (IOException stoppedOrFailed) {
                if (!closed.get() && !server.isClosed()) return;
            }
        }
    }

    private void pipe(Socket client, String upstreamAddress) {
        try (Socket upstream = new Socket()) {
            upstream.connect(endpoint(upstreamAddress), CONNECT_TIMEOUT_MS);
            upstream.setTcpNoDelay(true);
            client.setTcpNoDelay(true);
            copy(client, upstream);
        } catch (Exception unavailable) {
            // The Psiphon listener is not accepting: the client gets a closed connection, which
            // every proxy client treats as "try again". Nothing is retried here on purpose - the
            // readiness gate guarantees the upstream is live before the relays start.
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private static void copy(Socket client, Socket upstream) throws Exception {
        // Two unidirectional pipe threads per connection; the first direction to fail or finish
        // closes both sockets, which unblocks the other thread.
        Thread toUpstream = new Thread(() -> transfer(client, upstream));
        Thread toClient = new Thread(() -> transfer(upstream, client));
        toUpstream.setDaemon(true);
        toClient.setDaemon(true);
        toUpstream.start();
        toClient.start();
        toUpstream.join();
        toClient.join();
    }

    private static void transfer(Socket from, Socket to) {
        byte[] buffer = new byte[PIPE_BUFFER];
        try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (Exception finished) {
            // A closed direction ends the pipe; the peer socket close below ends the other side.
        } finally {
            try { from.close(); } catch (IOException ignored) { }
            try { to.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (servers) {
            for (ServerSocket server : servers) {
                try { server.close(); } catch (IOException ignored) { }
            }
            servers.clear();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static InetSocketAddress endpoint(String address) {
        if (!CoreSettings.endpoint(address, true)) throw new IllegalArgumentException("Relay endpoint must use a loopback IP:port");
        int colon = address.lastIndexOf(':');
        return new InetSocketAddress(address.substring(0, colon).replace("[", "").replace("]", ""),
                Integer.parseInt(address.substring(colon + 1)));
    }
}
