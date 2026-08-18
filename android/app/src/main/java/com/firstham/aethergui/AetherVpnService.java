package com.firstham.aethergui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import hev.htproxy.TProxyService;

public final class AetherVpnService extends VpnService {
    public static final String ACTION_START = "com.firstham.aethergui.START";
    public static final String ACTION_STOP = "com.firstham.aethergui.STOP";
    public static final String ACTION_QUERY = "com.firstham.aethergui.QUERY";
    public static final String ACTION_STATUS = "com.firstham.aethergui.STATUS";
    public static final String ACTION_LOG = "com.firstham.aethergui.LOG";
    public static final String ACTION_STATS = "com.firstham.aethergui.STATS";
    public static final String ACTION_CLEAR_LOGS = "com.firstham.aethergui.CLEAR_LOGS";
    public static final String INTERNAL_PERMISSION = "io.github.hamvex.aethergui.permission.INTERNAL";
    private static final String CHANNEL_ID = "aether_vpn";
    private static final int NOTIFICATION_ID = 1819;
    private static final int SOCKS_TIMEOUT_MS = 120_000;
    private static final int SMART_PROTOCOL_TIMEOUT_MS = 35_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final String TAG = "AetherVpnService";

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ScheduledExecutorService telemetry = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean();
    private final AtomicLong locationLookupSequence = new AtomicLong();
    private final AtomicBoolean recoveryRestartPending = new AtomicBoolean();
    private final Object runtimeLock = new Object();
    private final Object networkLock = new Object();
    private final Object logLock = new Object();
    private final StringBuilder logHistory = new StringBuilder();
    private final StringBuilder pendingLogs = new StringBuilder();
    private volatile Process aetherProcess;
    private volatile ParcelFileDescriptor vpnInterface;
    private volatile boolean bridgeStarted;
    private volatile boolean stopping = true;
    private volatile boolean active;
    private volatile boolean killSwitch;
    private volatile boolean smartBenchmarking;
    private volatile boolean masqueH3GatewayUnavailable;
    private volatile String currentState = "disconnected";
    private volatile String currentMessage = "Ready to connect";
    private volatile String currentEndpoint = "";
    private volatile long connectedAt;
    private volatile long lastLogPersistedAt;
    private volatile long lastHealthCheckAt;
    private volatile long lastPing = -1;
    private volatile int consecutiveHealthFailures;
    private volatile Intent activeRequest;
    private final Set<Network> availableNetworks = ConcurrentHashMap.newKeySet();
    private volatile boolean networkUnavailable;
    private volatile boolean connectionEstablished;
    private SharedPreferences stateStore;
    private ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            boolean recovering = networkUnavailable || availableNetworks.isEmpty();
            availableNetworks.add(network);
            networkUnavailable = false;
            synchronized (networkLock) { networkLock.notifyAll(); }
            if (recovering) requestCoreRecovery(getString(R.string.service_network_restored));
        }

        @Override public void onLost(Network network) {
            availableNetworks.remove(network);
            if (!availableNetworks.isEmpty() || !active || stopping) return;
            networkUnavailable = true;
            updateState("reconnecting", getString(R.string.service_network_lost));
            updateNotification(getString(R.string.service_network_lost));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        stateStore = getSharedPreferences("service_state", MODE_PRIVATE);
        String savedLogs = stateStore.getString("logs", "");
        if (savedLogs != null) logHistory.append(savedLogs);
        createNotificationChannel();
        connectivityManager = getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
        telemetry.scheduleWithFixedDelay(this::publishStats, 1, 1, TimeUnit.SECONDS);
        telemetry.scheduleWithFixedDelay(this::flushLogs, 75, 75, TimeUnit.MILLISECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (stateStore.getBoolean("desiredConnected", false) && VpnService.prepare(this) == null) {
                return onStartCommand(VpnConnectionController.startIntent(this, getSharedPreferences("aether", MODE_PRIVATE)), flags, startId);
            }
            return active ? START_STICKY : START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_QUERY.equals(action)) {
            sendStatus(currentState, currentMessage);
            publishStats();
            if (!active) stopSelf(startId);
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_CLEAR_LOGS.equals(action)) {
            synchronized (logLock) {
                logHistory.setLength(0);
                pendingLogs.setLength(0);
            }
            stateStore.edit().remove("logs").apply();
            if (!active) stopSelf(startId);
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stateStore.edit().putBoolean("desiredConnected", false).apply();
            generation.incrementAndGet();
            stopping = true;
            updateState("disconnecting", getString(R.string.service_disconnecting));
            worker.execute(() -> stopConnection(true));
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            stateStore.edit().putBoolean("desiredConnected", true).apply();
            Intent request = new Intent(intent);
            long session = generation.incrementAndGet();
            stopping = true;
            updateState("starting", getString(R.string.service_preparing));
            startForegroundCompat(notification(getString(R.string.service_preparing), false));
            worker.execute(() -> {
                stopConnection(false);
                if (generation.get() != session) return;
                activeRequest = request;
                stopping = false;
                active = true;
                killSwitch = request.getBooleanExtra("killSwitch", false);
                runConnection(request, session);
            });
            return START_STICKY;
        }
        return active ? START_STICKY : START_NOT_STICKY;
    }

    private void runConnection(Intent request, long session) {
        try {
            currentEndpoint = reliableEndpoint(request);
            String connectionMode = value(request, "connectionMode", "vpn");
            if ("smart".equals(connectionMode)) {
                updateState("smart-testing", getString(R.string.service_smart_testing));
                String protocol = chooseSmartProtocol(request, session);
                request.putExtra("protocol", protocol);
                stateStore.edit().putString("smartProtocol", protocol).apply();
                sendLog("Smart Connect selected " + protocolLabel(protocol));
                currentEndpoint = reliableEndpoint(request);
            }
            updateState("starting", getString(R.string.service_launching));
            updateState("scanning", getString(R.string.service_scanning));
            boolean socksReady = startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS);
            if (!socksReady) {
                throw new IllegalStateException(aetherExitMessage("Aether did not open its SOCKS5 listener"));
            }

            connectionEstablished = true;
            if ("manual".equals(connectionMode)) {
                connectedAt = System.currentTimeMillis();
                updateState("connected", getString(R.string.service_proxy_ready));
                updateNotification(getString(R.string.service_proxy_connected));
            } else {
                establishVpn(request);
                connectedAt = System.currentTimeMillis();
                updateState("connected", getString(R.string.service_protected));
                updateNotification(getString("smart".equals(connectionMode) ? R.string.service_smart_protected : R.string.service_aethon_protected));
            }
            scheduleLocationLookup(request, session);
            monitorAether(request, session);
        } catch (Exception error) {
            if (stopping || generation.get() != session) return;
            Log.e(TAG, "Connection failed", error);
            sendLog("Error: " + safeMessage(error));
            if (killSwitch && vpnInterface != null && !stopping) {
                stopAetherOnly();
                updateState("blocked", getString(R.string.service_blocked));
                updateNotification(getString(R.string.service_blocked_notification));
            } else {
                stopRuntime();
                stateStore.edit().putBoolean("desiredConnected", false).apply();
                updateState("error", getString(R.string.status_error));
                stopForeground(STOP_FOREGROUND_REMOVE);
                active = false;
                stopSelf();
            }
        }
    }

    private void establishVpn(Intent request) throws Exception {
        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(request.getIntExtra("mtu", 1500))
                .setBlocking(false)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126);

        String routing = value(request, "routing", "bypass-local");
        if ("bypass-local".equals(routing)) addPublicRoutes(builder);
        else builder.addRoute("0.0.0.0", 0).addRoute("::", 0);

        if (request.getBooleanExtra("dnsLeak", true)) {
            builder.addDnsServer("1.1.1.1").addDnsServer("1.0.0.1");
        }
        applySplitApps(builder, request);
        vpnInterface = builder.establish();
        if (vpnInterface == null) throw new IllegalStateException("Android could not create the VPN interface");

        File config = writeTunConfig(request);
        try {
            TProxyService.TProxyStartService(config.getAbsolutePath(), vpnInterface.getFd());
            bridgeStarted = true;
            sendLog("HEV Android TUN bridge started");
        } catch (UnsatisfiedLinkError error) {
            throw new IllegalStateException("The HEV Android JNI bridge could not be loaded", error);
        }
    }

    private void startAether(Intent request) throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libaether.so");
        if (!executable.isFile()) throw new IllegalStateException("Aether core is missing for this device architecture");

        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath());
        builder.directory(getFilesDir());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("AETHER_PROTOCOL", value(request, "protocol", ConnectionDefaults.PROTOCOL));
        env.put("AETHER_SCAN", value(request, "scan", ConnectionDefaults.SCAN));
        env.put("AETHER_IP", value(request, "ipMode", "v4"));
        env.put("AETHER_NOIZE", value(request, "obfuscation", "firewall"));
        env.put("AETHER_LOG_LEVEL", value(request, "logLevel", "info"));
        env.put("AETHER_SOCKS", value(request, "socks", "127.0.0.1:1819"));
        env.put("AETHER_CONFIG", new File(getFilesDir(), "aether.toml").getAbsolutePath());
        env.put("AETHER_QUICK_RECONNECT", request.getBooleanExtra("quickReconnect", true) ? "1" : "0");
        String protocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        String transport = value(request, "transport", "h3");
        if ("masque".equals(protocol)) env.put("AETHER_MASQUE_HTTP2", "h2".equals(transport) ? "1" : "0");
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        String peer = request.getStringExtra("peer");
        if (peer != null && !peer.trim().isEmpty()) env.put("AETHER_PEER", peer.trim());

        masqueH3GatewayUnavailable = false;

        synchronized (runtimeLock) {
            aetherProcess = builder.start();
        }
        Process process = aetherProcess;
        sendLog("Aether core started for " + Build.SUPPORTED_ABIS[0]);
        Thread logs = new Thread(() -> readAetherLogs(process, protocol, transport), "aether-log-reader");
        logs.setDaemon(true);
        logs.start();
    }

    private void readAetherLogs(Process process, String protocol, String transport) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Aether] " + line);
                String lower = line.toLowerCase(Locale.US);
                if (process == aetherProcess && "masque".equals(protocol) && "h3".equals(transport)
                        && lower.contains("no usable masque gateway found")) {
                    masqueH3GatewayUnavailable = true;
                }
                if (!smartBenchmarking) {
                    if (lower.contains("identity ready")) updateState("scanning", getString(R.string.service_identity_ready));
                    if (lower.contains("hunting for")) updateState("scanning", getString(R.string.service_testing_gateways));
                    if (lower.contains("validated") || lower.contains("passed handshake")) updateState("securing", getString(R.string.service_gateway_verified));
                }
            }
        } catch (Exception error) {
            if (!stopping) sendLog("Aether log stream closed: " + safeMessage(error));
        }
    }

    private void monitorAether(Intent request, long session) throws Exception {
        int attempts = 0;
        while (!stopping && generation.get() == session) {
            Process process = aetherProcess;
            if (process == null) return;
            long processStartedAt = System.currentTimeMillis();
            int exitCode = process.waitFor();
            if (stopping || generation.get() != session) return;
            sendLog("Aether exited with code " + exitCode);
            if (!request.getBooleanExtra("quickReconnect", true)) {
                throw new IllegalStateException("Aether stopped unexpectedly (exit " + exitCode + ")");
            }
            waitForUnderlyingNetwork(session);
            if (stopping || generation.get() != session) return;
            if (System.currentTimeMillis() - processStartedAt >= 60_000L) attempts = 0;
            attempts++;
            if (attempts > MAX_RECONNECT_ATTEMPTS) {
                throw new IllegalStateException(getString(R.string.service_reconnect_failed, MAX_RECONNECT_ATTEMPTS));
            }
            currentEndpoint = "";
            updateState("reconnecting", getString(R.string.service_reconnecting));
            Thread.sleep(Math.min(20_000L, 1_500L << (attempts - 1)));
            if (!startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS)) {
                sendLog(aetherExitMessage("Aether reconnect attempt did not become ready"));
                Process retry = aetherProcess;
                if (retry != null && retry.isAlive()) retry.destroy();
                continue;
            }
            recoveryRestartPending.set(false);
            updateState("connected", getString(R.string.service_restored));
            updateNotification(getString(R.string.service_restored));
            scheduleLocationLookup(request, session);
        }
    }

    private void waitForUnderlyingNetwork(long session) throws InterruptedException {
        while (networkUnavailable && !stopping && generation.get() == session) {
            updateState("reconnecting", getString(R.string.service_network_lost));
            synchronized (networkLock) { networkLock.wait(30_000L); }
        }
    }

    private boolean waitForSocks(String address, long timeoutMs) {
        HostPort target;
        try { target = HostPort.parse(address); }
        catch (IllegalArgumentException error) { throw error; }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping && System.currentTimeMillis() < deadline) {
            Process process = aetherProcess;
            if (process != null && !process.isAlive()) return false;
            if (masqueH3GatewayUnavailable) return false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.host, target.port), 700);
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(400); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private boolean startAetherWithMasqueFallback(Intent request, long timeoutMs) throws Exception {
        startAether(request);
        String socks = value(request, "socks", "127.0.0.1:1819");
        if (waitForSocks(socks, timeoutMs)) return true;
        if (stopping || !"masque".equals(value(request, "protocol", ConnectionDefaults.PROTOCOL))
                || !"h3".equals(value(request, "transport", "h3")) || !masqueH3GatewayUnavailable) {
            return false;
        }
        sendLog("MASQUE HTTP/3 gateway scan failed; retrying with HTTP/2 transport");
        stopAetherOnly();
        request.putExtra("transport", "h2");
        updateState("scanning", getString(R.string.service_scanning));
        startAether(request);
        return waitForSocks(socks, timeoutMs);
    }

    private String chooseSmartProtocol(Intent request, long session) throws Exception {
        String[] protocols = {"masque", "wg", "gool"};
        SmartResult best = null;
        smartBenchmarking = true;
        try {
            for (int index = 0; index < protocols.length; index++) {
                if (stopping || generation.get() != session) throw new InterruptedException("Smart Connect was cancelled");
                String protocol = protocols[index];
                updateState("smart-testing", getString(R.string.service_testing_protocol, protocolLabel(protocol), index + 1, protocols.length));
                Intent trial = new Intent(request).putExtra("protocol", protocol).putExtra("quickReconnect", false);
                SmartResult result = benchmarkProtocol(trial, protocol);
                sendLog(result.summary());
                if (best == null || result.score > best.score) best = result;
                stopAetherOnly();
                Thread.sleep(500);
            }
        } finally {
            smartBenchmarking = false;
            stopAetherOnly();
        }
        if (best == null || !best.connected) throw new IllegalStateException("Smart Connect could not establish any available protocol");
        return best.protocol;
    }

    private SmartResult benchmarkProtocol(Intent request, String protocol) {
        long started = System.nanoTime();
        try {
            startAether(request);
            String socks = value(request, "socks", "127.0.0.1:1819");
            boolean connected = waitForSocks(socks, SMART_PROTOCOL_TIMEOUT_MS);
            long handshakeMs = elapsedMillis(started);
            if (!connected) return SmartResult.failed(protocol, handshakeMs);
            long latencyMs = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
            long dnsMs = socksConnectMillis(socks, "cloudflare.com", 443, 5_000);
            int attempts = 5;
            int stable = 0;
            long latencyTotal = 0;
            for (int i = 0; i < attempts; i++) {
                if (stopping) break;
                try {
                    long probe = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
                    latencyTotal += probe;
                    stable++;
                } catch (Exception ignored) { }
                try { Thread.sleep(600); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            if (stable > 0) latencyMs = Math.min(latencyMs, latencyTotal / stable);
            return SmartResult.success(protocol, handshakeMs, latencyMs, dnsMs, stable, attempts);
        } catch (Throwable error) {
            return SmartResult.failed(protocol, elapsedMillis(started));
        }
    }

    private Socket openSocksTunnel(String socksAddress, String host, int port, int timeoutMs) throws Exception {
        HostPort proxy = HostPort.parse(socksAddress);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxy.host, proxy.port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            output.write(new byte[]{5, 1, 0});
            output.flush();
            byte[] greeting = readExact(input, 2);
            if (greeting[0] != 5 || greeting[1] != 0) throw new IllegalStateException("SOCKS5 authentication failed");
            byte[] ipv4 = parseIpv4Address(host);
            if (ipv4 != null) {
                output.write(new byte[]{5, 1, 0, 1});
                output.write(ipv4);
            } else {
                byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
                if (hostBytes.length > 255) throw new IllegalArgumentException("SOCKS5 host is too long");
                output.write(new byte[]{5, 1, 0, 3, (byte) hostBytes.length});
                output.write(hostBytes);
            }
            output.write(new byte[]{(byte) (port >>> 8), (byte) port});
            output.flush();
            byte[] response = readExact(input, 4);
            if (response[0] != 5 || response[1] != 0) throw new IllegalStateException("SOCKS5 connection failed");
            int addressLength;
            if (response[3] == 1) addressLength = 4;
            else if (response[3] == 4) addressLength = 16;
            else if (response[3] == 3) addressLength = readExact(input, 1)[0] & 0xff;
            else throw new IllegalStateException("Invalid SOCKS5 response");
            readExact(input, addressLength + 2);
            return socket;
        } catch (Throwable error) {
            try { socket.close(); } catch (Exception ignored) { }
            throw error;
        }
    }

    private long socksConnectMillis(String socksAddress, String host, int port, int timeoutMs) throws Exception {
        long started = System.nanoTime();
        try (Socket socket = openSocksTunnel(socksAddress, host, port, timeoutMs)) {
            return elapsedMillis(started);
        }
    }

    private void scheduleLocationLookup(Intent request, long session) {
        long lookup = locationLookupSequence.incrementAndGet();
        currentEndpoint = getString(R.string.location_detecting);
        sendStatus(currentState, currentMessage);
        worker.execute(() -> {
            String location = "";
            for (int attempt = 0; attempt < 4 && lookup == locationLookupSequence.get() && !stopping
                    && generation.get() == session && "connected".equals(currentState); attempt++) {
                    try {
                        String ip = socksHttpGet(value(request, "socks", "127.0.0.1:1819"), "api.ipify.org", "/?format=json");
                        JSONObject ipJson = new JSONObject(ip);
                        String address = ipJson.optString("ip", "").trim();
                        if (address.isEmpty()) throw new IllegalStateException("VPN public IP was empty");
                        String socksAddress = value(request, "socks", "127.0.0.1:1819");
                        try {
                            location = locationFromJson(new JSONObject(socksHttpGet(socksAddress, "ipapi.co", "/" + address + "/json/")));
                        } catch (Throwable primaryError) {
                            sendLog("Primary VPN location provider failed; trying fallback: " + safeMessage(primaryError));
                            location = locationFromJson(new JSONObject(socksHttpGet(socksAddress, "ipwho.is", "/" + address)));
                        }
                        if (!location.isEmpty()) break;
                    } catch (Throwable error) {
                        if (attempt == 3) sendLog("VPN location lookup failed: " + safeMessage(error));
                        try { Thread.sleep(5_000L * (attempt + 1)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    }
            }
            if (lookup == locationLookupSequence.get() && !stopping && generation.get() == session && "connected".equals(currentState)) {
                currentEndpoint = location.isEmpty() ? getString(R.string.connection_location_unavailable) : location;
                sendStatus(currentState, currentMessage);
            }
        });
    }

    private String socksHttpGet(String socksAddress, String host, String path) throws Exception {
        try (Socket tunnel = openSocksTunnel(socksAddress, host, 443, 10_000)) {
            try (SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tunnel, host, 443, true)) {
            ssl.setSoTimeout(10_000);
            ssl.startHandshake();
            OutputStream output = ssl.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nAccept: application/json\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int status = 0;
            StringBuilder body = new StringBuilder();
            boolean headers = true;
            while ((line = reader.readLine()) != null) {
                if (headers) {
                    if (line.startsWith("HTTP/")) status = Integer.parseInt(line.split(" ", 3)[1]);
                    if (line.isEmpty()) headers = false;
                } else body.append(line);
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("Location service returned HTTP " + status);
            return body.toString();
            }
        }
    }

    private static String locationFromJson(JSONObject geo) {
        if (geo.optBoolean("error", false) || (geo.has("success") && !geo.optBoolean("success", true))) return "";
        String city = geo.optString("city", "").trim();
        String country = geo.optString("country_code", geo.optString("countryCode", "")).trim().toUpperCase(Locale.US);
        if (!city.isEmpty() && !country.isEmpty()) return countryFlag(country) + " " + city;
        if (!country.isEmpty()) return countryFlag(country) + " " + country;
        return "";
    }

    private static String countryFlag(String country) {
        if (country.length() != 2) return "";
        return new String(Character.toChars(0x1F1E6 + country.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + country.charAt(1) - 'A'));
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(value, offset, length - offset);
            if (read < 0) throw new IllegalStateException("SOCKS5 response ended early");
            offset += read;
        }
        return value;
    }

    private static byte[] parseIpv4Address(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) return null;
                address[i] = (byte) value;
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return address;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String protocolLabel(String protocol) {
        if ("wg".equals(protocol)) return "WireGuard";
        if ("gool".equals(protocol)) return "gool / WARP-in-WARP";
        return "MASQUE";
    }

    private File writeTunConfig(Intent request) throws Exception {
        HostPort socks = HostPort.parse(value(request, "socks", "127.0.0.1:1819"));
        File config = new File(getCacheDir(), "hev.yml");
        try (FileWriter writer = new FileWriter(config, false)) {
            writer.write("misc:\n");
            writer.write("  task-stack-size: 32768\n");
            writer.write("  connect-timeout: 15000\n");
            writer.write("  log-level: warn\n");
            writer.write("tunnel:\n");
            writer.write("  mtu: " + request.getIntExtra("mtu", 1500) + "\n");
            writer.write("  ipv4: 198.18.0.1\n");
            writer.write("  ipv6: 'fc00::1'\n");
            writer.write("  icmp: 'reply'\n");
            writer.write("socks5:\n");
            writer.write("  address: '" + yamlEscape(socks.host) + "'\n");
            writer.write("  port: " + socks.port + "\n");
            writer.write("  udp: 'udp'\n");
        }
        return config;
    }

    private void applySplitApps(Builder builder, Intent request) {
        String mode = value(request, "routing", "bypass-local");
        String apps = value(request, "splitApps", "");
        boolean includeOnly = "split-include".equals(mode);
        if (!includeOnly) {
            try { builder.addDisallowedApplication(getPackageName()); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (apps.trim().isEmpty()) return;
        int valid = 0;
        for (String packageName : apps.split("[\\r\\n,]+")) {
            packageName = packageName.trim();
            if (packageName.isEmpty() || packageName.equals(getPackageName())) continue;
            try {
                if (includeOnly) builder.addAllowedApplication(packageName);
                else if ("split-exclude".equals(mode)) builder.addDisallowedApplication(packageName);
                valid++;
            } catch (PackageManager.NameNotFoundException error) {
                sendLog("Unknown Android package: " + packageName);
            }
        }
        if (includeOnly && valid == 0) {
            throw new IllegalArgumentException("Include selected apps requires at least one valid Android package name");
        }
    }

    private void addPublicRoutes(Builder builder) {
        List<Ipv4Range> excluded = new ArrayList<>();
        excluded.add(Ipv4Range.cidr("0.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("10.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("100.64.0.0", 10));
        excluded.add(Ipv4Range.cidr("127.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("169.254.0.0", 16));
        excluded.add(Ipv4Range.cidr("172.16.0.0", 12));
        excluded.add(Ipv4Range.cidr("192.0.0.0", 24));
        excluded.add(Ipv4Range.cidr("192.168.0.0", 16));
        excluded.add(Ipv4Range.cidr("198.18.0.0", 15));
        excluded.add(Ipv4Range.cidr("224.0.0.0", 3));
        Collections.sort(excluded);
        long cursor = 0;
        for (Ipv4Range range : excluded) {
            if (cursor < range.start) addRangeAsRoutes(builder, cursor, range.start - 1);
            cursor = Math.max(cursor, range.end + 1);
        }
        if (cursor <= 0xffffffffL) addRangeAsRoutes(builder, cursor, 0xffffffffL);
        builder.addRoute("2000::", 3);
    }

    private void addRangeAsRoutes(Builder builder, long start, long end) {
        while (start <= end) {
            long alignment = start == 0 ? (1L << 32) : Long.lowestOneBit(start);
            long remaining = end - start + 1;
            long block = alignment;
            while (block > remaining) block >>>= 1;
            int prefix = 32 - Long.numberOfTrailingZeros(block);
            builder.addRoute(Ipv4Range.format(start), prefix);
            start += block;
        }
    }

    private void publishStats() {
        if (!active) return;
        long tx = 0;
        long rx = 0;
        if (bridgeStarted) {
            try {
                long[] stats = TProxyService.TProxyGetStats();
                if (stats != null && stats.length >= 4) { tx = stats[1]; rx = stats[3]; }
            } catch (Throwable error) {
                Log.w(TAG, "Could not read HEV stats", error);
            }
        }
        maybeCheckTunnelHealth();
        Intent intent = new Intent(ACTION_STATS).setPackage(getPackageName());
        intent.putExtra("tx", tx).putExtra("rx", rx).putExtra("ping", lastPing).putExtra("connectedAt", connectedAt);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private void maybeCheckTunnelHealth() {
        if (!"connected".equals(currentState) || networkUnavailable || System.currentTimeMillis() - lastHealthCheckAt < 15_000L || !healthCheckRunning.compareAndSet(false, true)) return;
        lastHealthCheckAt = System.currentTimeMillis();
        Intent request = activeRequest;
        worker.execute(() -> {
            try {
                if (request == null || stopping || !active) return;
                lastPing = socksConnectMillis(value(request, "socks", "127.0.0.1:1819"), "1.1.1.1", 443, 4_000);
                consecutiveHealthFailures = 0;
            } catch (Throwable error) {
                lastPing = -1;
                if (++consecutiveHealthFailures >= 3) {
                    consecutiveHealthFailures = 0;
                    requestCoreRecovery(getString(R.string.service_tunnel_unresponsive));
                }
            } finally {
                healthCheckRunning.set(false);
            }
        });
    }

    private void requestCoreRecovery(String message) {
        Intent request = activeRequest;
        if (!active || stopping || request == null || !request.getBooleanExtra("quickReconnect", true)) return;
        updateState("reconnecting", message);
        updateNotification(message);
        if (!connectionEstablished || !recoveryRestartPending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            synchronized (runtimeLock) {
                Process process = aetherProcess;
                if (process != null && process.isAlive()) process.destroy();
            }
        });
    }

    private void stopConnection(boolean userInitiated) {
        stopping = true;
        stopRuntime();
        active = false;
        connectedAt = 0;
        currentEndpoint = "";
        locationLookupSequence.incrementAndGet();
        activeRequest = null;
        connectionEstablished = false;
        recoveryRestartPending.set(false);
        lastPing = -1;
        consecutiveHealthFailures = 0;
        if (userInitiated) {
            updateState("disconnected", getString(R.string.service_disconnected));
        }
        if (userInitiated) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopRuntime() {
        synchronized (runtimeLock) {
            if (bridgeStarted) {
                try { TProxyService.TProxyStopService(); }
                catch (Throwable error) { Log.w(TAG, "Could not stop HEV", error); }
                bridgeStarted = false;
            }
            try { if (vpnInterface != null) vpnInterface.close(); }
            catch (Exception ignored) { }
            vpnInterface = null;
            stopAetherOnly();
        }
    }

    private void stopAetherOnly() {
        Process process = aetherProcess;
        aetherProcess = null;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    private String aetherExitMessage(String fallback) {
        Process process = aetherProcess;
        if (process != null && !process.isAlive()) {
            try { return fallback + " (exit " + process.exitValue() + ")"; }
            catch (IllegalThreadStateException ignored) { }
        }
        return fallback;
    }

    private void updateState(String state, String message) {
        currentState = state;
        currentMessage = message == null ? "" : message;
        stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", currentEndpoint).apply();
        sendStatus(currentState, currentMessage);
        AethonTileService.requestUpdate(this);
    }

    private void sendStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra("state", state).putExtra("message", message).putExtra("endpoint", currentEndpoint);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private void sendLog(String line) {
        if (line == null || line.trim().isEmpty()) return;
        Log.i(TAG, line);
        synchronized (logLock) {
            if (logHistory.length() > 0) logHistory.append('\n');
            logHistory.append(line);
            trimLog(logHistory, 24_000);
            if (pendingLogs.length() > 0) pendingLogs.append('\n');
            pendingLogs.append(line);
        }
    }

    private void flushLogs() {
        String batch;
        String history;
        synchronized (logLock) {
            if (pendingLogs.length() == 0) return;
            batch = pendingLogs.toString();
            pendingLogs.setLength(0);
            history = logHistory.toString();
        }
        long now = System.currentTimeMillis();
        if (now - lastLogPersistedAt >= 500) {
            stateStore.edit().putString("logs", history).apply();
            lastLogPersistedAt = now;
        }
        Intent intent = new Intent(ACTION_LOG).setPackage(getPackageName()).putExtra("lines", batch);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private static void trimLog(StringBuilder value, int maxLength) {
        if (value.length() <= maxLength) return;
        int cut = value.length() - maxLength;
        int newline = value.indexOf("\n", cut);
        value.delete(0, newline >= 0 ? newline + 1 : cut);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_summary));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text, boolean connected) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AetherVpnService.class).setAction(ACTION_STOP);
        PendingIntent disconnect = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_aethon_mono)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (connected || active) builder.addAction(0, getString(R.string.disconnect), disconnect);
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text, true));
    }

    @Override public void onRevoke() {
        stateStore.edit().putBoolean("desiredConnected", false).apply();
        generation.incrementAndGet();
        stopping = true;
        worker.execute(() -> stopConnection(true));
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopping = true;
        stopRuntime();
        flushLogs();
        synchronized (logLock) {
            stateStore.edit().putString("logs", logHistory.toString()).apply();
        }
        if (VpnConnectionController.canDisconnect(currentState)) {
            currentState = "disconnected";
            currentMessage = getString(R.string.service_disconnected);
            stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", "").apply();
            AethonTileService.requestUpdate(this);
        }
        telemetry.shutdownNow();
        worker.shutdownNow();
        try { connectivityManager.unregisterNetworkCallback(networkCallback); }
        catch (RuntimeException error) { Log.w(TAG, "Network callback was already unregistered", error); }
        super.onDestroy();
    }

    private static String value(Intent intent, String key, String fallback) {
        String result = intent.getStringExtra(key);
        return result == null || result.trim().isEmpty() ? fallback : result.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String yamlEscape(String value) { return value.replace("'", "''"); }

    private static String reliableEndpoint(Intent request) {
        String peer = request.getStringExtra("peer");
        return peer == null ? "" : peer.trim();
    }

    private static final class SmartResult {
        final String protocol;
        final boolean connected;
        final long handshakeMs;
        final long latencyMs;
        final long dnsMs;
        final int stableProbes;
        final int attempts;
        final double score;

        private SmartResult(String protocol, boolean connected, long handshakeMs, long latencyMs, long dnsMs, int stableProbes, int attempts, double score) {
            this.protocol = protocol;
            this.connected = connected;
            this.handshakeMs = handshakeMs;
            this.latencyMs = latencyMs;
            this.dnsMs = dnsMs;
            this.stableProbes = stableProbes;
            this.attempts = attempts;
            this.score = score;
        }

        static SmartResult failed(String protocol, long handshakeMs) {
            return new SmartResult(protocol, false, handshakeMs, -1, -1, 0, 5, 0);
        }

        static SmartResult success(String protocol, long handshakeMs, long latencyMs, long dnsMs, int stableProbes, int attempts) {
            double handshakeScore = 30.0 * clamp(1.0 - handshakeMs / (double) SMART_PROTOCOL_TIMEOUT_MS);
            double latencyScore = 15.0 * clamp(1.0 - latencyMs / 2_000.0);
            double stabilityScore = 5.0 * stableProbes / Math.max(1, attempts);
            return new SmartResult(protocol, true, handshakeMs, latencyMs, dnsMs, stableProbes, attempts, 50.0 + handshakeScore + latencyScore + stabilityScore);
        }

        String summary() {
            double loss = attempts == 0 ? 100 : 100.0 * (attempts - stableProbes) / attempts;
            return String.format(Locale.US, "Smart Connect %s: success=%s handshake=%dms latency=%dms dns=%dms loss=%.0f%% stability=%d/%d score=%.1f", protocolLabel(protocol), connected, handshakeMs, latencyMs, dnsMs, loss, stableProbes, attempts, score);
        }

        private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    }

    private static final class HostPort {
        final String host;
        final int port;
        private HostPort(String host, int port) { this.host = host; this.port = port; }
        static HostPort parse(String value) {
            if (value == null) throw new IllegalArgumentException("SOCKS5 address is missing");
            String input = value.trim();
            String host;
            String portValue;
            if (input.startsWith("[")) {
                int end = input.indexOf(']');
                if (end < 0 || end + 2 > input.length() || input.charAt(end + 1) != ':') throw new IllegalArgumentException("Invalid SOCKS5 address");
                host = input.substring(1, end);
                portValue = input.substring(end + 2);
            } else {
                int split = input.lastIndexOf(':');
                if (split <= 0) throw new IllegalArgumentException("SOCKS5 address must use host:port");
                host = input.substring(0, split);
                portValue = input.substring(split + 1);
            }
            int port;
            try { port = Integer.parseInt(portValue); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid SOCKS5 port"); }
            if (host.trim().isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SOCKS5 address");
            return new HostPort(host.trim(), port);
        }
    }

    private static final class Ipv4Range implements Comparable<Ipv4Range> {
        final long start;
        final long end;
        private Ipv4Range(long start, long end) { this.start = start; this.end = end; }
        static Ipv4Range cidr(String address, int prefix) {
            long value = parse(address);
            long size = 1L << (32 - prefix);
            return new Ipv4Range(value, value + size - 1);
        }
        static long parse(String address) {
            String[] parts = address.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
            long value = 0;
            for (String part : parts) value = (value << 8) | Integer.parseInt(part);
            return value;
        }
        static String format(long value) {
            return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
        }
        @Override public int compareTo(Ipv4Range other) { return Long.compare(start, other.start); }
    }
}
