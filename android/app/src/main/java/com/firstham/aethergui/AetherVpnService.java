package com.firstham.aethergui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hev.htproxy.TProxyService;

public final class AetherVpnService extends VpnService {
    public static final String ACTION_START = "com.firstham.aethergui.START";
    public static final String ACTION_STOP = "com.firstham.aethergui.STOP";
    public static final String ACTION_STATUS = "com.firstham.aethergui.STATUS";
    public static final String ACTION_LOG = "com.firstham.aethergui.LOG";
    private static final String CHANNEL_ID = "aether_vpn";
    private static final int NOTIFICATION_ID = 1819;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Process aetherProcess;
    private volatile ParcelFileDescriptor vpnInterface;
    private volatile boolean stopping;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            worker.execute(this::stopTunnel);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, notification("Preparing Aether"));
            Intent request = new Intent(intent);
            worker.execute(() -> startTunnel(request));
        }
        return START_STICKY;
    }

    private void startTunnel(Intent request) {
        stopTunnelInternal(false);
        stopping = false;
        sendStatus("scanning", "Starting Aether 1.5.0");
        try {
            startAether(request);
            if (!waitForSocks(request.getStringExtra("socks"), 90_000)) {
                throw new IllegalStateException("Aether did not open the SOCKS5 listener");
            }
            if ("manual".equals(request.getStringExtra("connectionMode"))) {
                sendStatus("connected", "SOCKS5 proxy is ready");
                updateNotification("Manual SOCKS5 is connected");
                watchProcess(request);
                return;
            }

            Builder builder = new Builder()
                    .setSession("Firstham AetherGui")
                    .setMtu(request.getIntExtra("mtu", 1500))
                    .addAddress("198.18.0.1", 30)
                    .addAddress("fc00::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0);
            if (request.getBooleanExtra("dnsLeak", true)) {
                builder.addDnsServer("1.1.1.1").addDnsServer("1.0.0.1");
            }
            applySplitApps(builder, request);
            vpnInterface = builder.establish();
            if (vpnInterface == null) throw new IllegalStateException("Android did not create the VPN interface");

            File config = writeTunConfig(request);
            boolean started = TProxyService.TProxyStartService(config.getAbsolutePath(), vpnInterface.getFd());
            if (!started) throw new IllegalStateException("Could not start the Android TUN bridge");
            sendStatus("connected", "Android VPN is connected");
            updateNotification("Protected through Aether");
            watchProcess(request);
        } catch (Exception error) {
            Log.e("AetherVpnService", "Tunnel failed", error);
            sendLog("Error: " + error.getMessage());
            sendStatus("error", error.getMessage());
            stopTunnelInternal(true);
        }
    }

    private void startAether(Intent request) throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libaether.so");
        if (!executable.isFile()) throw new IllegalStateException("Aether core is missing for this device ABI");
        executable.setExecutable(true, false);
        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("AETHER_PROTOCOL", value(request, "protocol", "masque"));
        env.put("AETHER_SCAN", value(request, "scan", "balanced"));
        env.put("AETHER_IP", value(request, "ipMode", "v4"));
        env.put("AETHER_NOIZE", value(request, "obfuscation", "firewall"));
        env.put("AETHER_LOG_LEVEL", value(request, "logLevel", "info"));
        env.put("AETHER_SOCKS", value(request, "socks", "127.0.0.1:1819"));
        env.put("AETHER_CONFIG", new File(getFilesDir(), "aether.toml").getAbsolutePath());
        env.put("AETHER_QUICK_RECONNECT", request.getBooleanExtra("quickReconnect", true) ? "1" : "0");
        env.put("AETHER_MASQUE_HTTP2", "h2".equals(request.getStringExtra("transport")) ? "1" : "0");
        String peer = request.getStringExtra("peer");
        if (peer != null && !peer.trim().isEmpty()) env.put("AETHER_PEER", peer.trim());
        aetherProcess = builder.start();
        Thread logs = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(aetherProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sendLog("[Aether] " + line);
            } catch (Exception ignored) { }
        }, "aether-log-reader");
        logs.setDaemon(true);
        logs.start();
    }

    private void watchProcess(Intent request) throws InterruptedException {
        Process process = aetherProcess;
        if (process == null) return;
        int exit = process.waitFor();
        if (stopping) return;
        sendLog("Aether exited with code " + exit);
        if (request.getBooleanExtra("quickReconnect", true)) {
            sendStatus("reconnecting", "Restarting Aether");
            try { Thread.sleep(1500); startTunnel(request); }
            catch (Exception error) { sendStatus("error", error.getMessage()); }
        } else {
            sendStatus("error", "Aether stopped unexpectedly");
            stopTunnelInternal(true);
        }
    }

    private boolean waitForSocks(String address, long timeoutMs) {
        String value = address == null || address.isEmpty() ? "127.0.0.1:1819" : address;
        int split = value.lastIndexOf(':');
        String host = split > 0 ? value.substring(0, split) : "127.0.0.1";
        int port = split > 0 ? Integer.parseInt(value.substring(split + 1)) : 1819;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping && System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 800);
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(500); } catch (InterruptedException error) { return false; }
            }
        }
        return false;
    }

    private File writeTunConfig(Intent request) throws Exception {
        String socks = value(request, "socks", "127.0.0.1:1819");
        int split = socks.lastIndexOf(':');
        String host = split > 0 ? socks.substring(0, split) : "127.0.0.1";
        String port = split > 0 ? socks.substring(split + 1) : "1819";
        File config = new File(getFilesDir(), "hev.yml");
        try (FileWriter writer = new FileWriter(config, false)) {
            writer.write("tunnel:\n  mtu: " + request.getIntExtra("mtu", 1500) + "\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n");
            writer.write("socks5:\n  address: '" + host + "'\n  port: " + port + "\n  udp: 'udp'\n");
            writer.write("misc:\n  log-level: warn\n  task-stack-size: 24576\n");
        }
        return config;
    }

    private void applySplitApps(Builder builder, Intent request) {
        String mode = value(request, "routing", "bypass-local");
        String apps = value(request, "splitApps", "");
        if (!"split-include".equals(mode)) {
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
        }
        if (apps.trim().isEmpty()) return;
        for (String packageName : apps.split("[\\r\\n,]+")) {
            packageName = packageName.trim();
            if (packageName.isEmpty() || packageName.equals(getPackageName())) continue;
            try {
                if ("split-include".equals(mode)) builder.addAllowedApplication(packageName);
                else if ("split-exclude".equals(mode)) builder.addDisallowedApplication(packageName);
            } catch (Exception error) { sendLog("Unknown Android package: " + packageName); }
        }
    }

    private static String value(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void stopTunnel() {
        stopTunnelInternal(true);
        sendStatus("disconnected", "Disconnected");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopTunnelInternal(boolean closeForeground) {
        stopping = true;
        try { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService(); } catch (Throwable ignored) { }
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) { }
        vpnInterface = null;
        Process process = aetherProcess;
        aetherProcess = null;
        if (process != null) process.destroy();
        if (closeForeground) stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void sendStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra("state", state).putExtra("message", message);
        sendBroadcast(intent, "io.github.hamvex.aethergui.permission.INTERNAL");
    }

    private void sendLog(String line) {
        Intent intent = new Intent(ACTION_LOG).setPackage(getPackageName()).putExtra("line", line);
        sendBroadcast(intent, "io.github.hamvex.aethergui.permission.INTERNAL");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Aether VPN", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Aether connection status");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Firstham AetherGui")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        stopTunnelInternal(false);
        worker.shutdownNow();
        super.onDestroy();
    }
}
