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
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorCompletionService;
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
    public static final String ACTION_SET_LAN = "com.firstham.aethergui.SET_LAN";
    public static final String INTERNAL_PERMISSION = "io.github.hamvex.aethergui.permission.INTERNAL";
    private static final String CHANNEL_ID = "aether_vpn";
    private static final String ALERT_CHANNEL_ID = "aether_vpn_alerts";
    private static final int NOTIFICATION_ID = 1819;
    private static final int ALERT_NOTIFICATION_ID = 1820;
    private static final int SOCKS_TIMEOUT_MS = 60_000;
    private static final int SMART_PROTOCOL_TIMEOUT_MS = 18_000;
    private static final double SMART_EARLY_ACCEPT_SCORE = 96.0;
    private static final int MASQUE_H3_PRIMARY_TIMEOUT_MS = 20_000;
    private static final int TRAFFIC_READY_TIMEOUT_MS = 4_000;
    /**
     * Per-probe budget for privacy-chain endpoints. A helper that just bootstrapped
     * legitimately needs longer than the WARP-direct 4s: Tor's first request includes circuit
     * selection, DNS over the chain and TLS, and every round trip also traverses the chain's
     * carrier. dev.036 physical evidence: on a MASQUE-carried Tor chain every first probe
     * timed out at 4s ("Read timed out") while Tor was fully ready and the underlay was
     * validated; the same first-probe churn appeared on MASQUE-carried Psiphon chains
     * (success only on attempt 2). WARP-direct endpoints keep the tight 4s budget whose
     * bimodal dead-endpoint reasoning is unaffected.
     */
    private static final int CHAIN_TRAFFIC_READY_TIMEOUT_MS = 12_000;
    private static final int TRAFFIC_READY_ATTEMPTS = 2;
    /** A recovered core only has to prove the data plane once; the monitor owns the retry schedule. */
    private static final int RECOVERY_TRAFFIC_READY_ATTEMPTS = 1;
    /**
     * Turbo scan hands over an endpoint pair the core has already marked
     * {@code validated (end-to-end data confirmed)}, but roughly half of those pairs still carry no
     * real HTTPS traffic. Measurement across 75 turbo attempts found 125 distinct endpoint pairs of
     * which 60 only ever carried traffic and 61 only ever timed out — outcomes are per-endpoint and
     * bimodal, so a dead pair never recovers and waiting longer cannot help. The cure is a fresh
     * pair, which only a core restart produces. These re-rolls keep tun0, routing and the HEV bridge
     * in place, so they are the "short controlled wait + retry" of the recovery requirement rather
     * than the full teardown {@link #prepareRetry} performs.
     */
    private static final int TRAFFIC_READY_ENDPOINT_ROLLS = 4;
    /** A re-rolled endpoint is a fresh pair, so one probe round decides it; a second only burns budget. */
    private static final int ROLL_TRAFFIC_READY_ATTEMPTS = 1;
    /** Lets the core's listener settle after a re-roll before the data plane is probed again. */
    private static final long TRAFFIC_READY_ROLL_SETTLE_MS = 250L;
    /**
     * SOCKS wait for a re-rolled core. A re-roll is a full core start, and the core re-runs its whole
     * gateway scan every time — that is precisely why it yields a fresh endpoint pair. Measured on
     * SM-A556E: a start that succeeds reaches {@code socks5 listening} 2.4-8.8s in, but one whose
     * first sweep finds nothing usable re-scans on a roughly 25s cycle. A shorter wait therefore
     * abandons cores that were still going to succeed: at 12s a re-roll was observed finding its
     * endpoints and then being cut off 9s before it could listen.
     */
    private static final long ROLL_SOCKS_TIMEOUT_MS = 30_000L;
    /**
     * Ceiling on the whole gate including re-rolls. The effective deadline is the earlier of this and
     * the connect watchdog less {@link #TRAFFIC_READY_WATCHDOG_MARGIN_MS}, so however long the scan
     * before it took, the gate reports its own clean failure instead of being cut off mid-probe.
     */
    private static final long TRAFFIC_READY_TOTAL_BUDGET_MS = 55_000L;
    /** Headroom the gate leaves the watchdog, so a bad endpoint fails with a reason, not a timeout. */
    private static final long TRAFFIC_READY_WATCHDOG_MARGIN_MS = 5_000L;
    /**
     * Least budget a re-roll is worth starting with: port release, settle, a SOCKS wait long enough to
     * cover a core that starts normally, and the probe round that has to follow it.
     */
    private static final long MIN_TRAFFIC_READY_ROLL_BUDGET_MS = 14_000L;
    /**
     * Bound on waiting for Core's own privacy-listener announcements ("psiphon is ready",
     * "tor is ready") before privacy endpoints are probed. Core only announces after the helper
     * established a tunnel (Tunnels &gt; 0), which on a cold start means downloading the server list
     * through the WARP tunnel first: observed 10-15s on SM-A556E. Probing before the announcement
     * only produces ECONNREFUSED, which the gate would then misread as a bad endpoint pair and
     * answer with a core restart that kills the bootstrapping helper — the exact death loop the
     * September 30 dev.014 validation reproduced. Waiting for the announcement keeps the helper
     * alive inside the same gate budget and watchdog; the WARP endpoints are unaffected because
     * they are not privacy endpoints.
     */
    private static final long PRIVACY_ANNOUNCEMENT_TIMEOUT_MS = 30_000L;
    /**
     * Extra startup allowance while a full-device Tor chain bootstraps: Tor's first-run
     * consensus download through the WARP underlay can take a minute or more, which would
     * otherwise trip both the 30s announcement wait and the connect watchdog.
     */
    private static final long TOR_CHAIN_STARTUP_ALLOWANCE_MS = 120_000L;
    /**
     * dev.034 fix: extra startup allowance for a Psiphon chain whose carrier is a MASQUE
     * tunnel (MASQUE, MIM, or Gool over MASQUE). Core v2.3.0 holds the Psiphon helper until
     * the carrier tunnel validates ("psiphon is waiting for the tunnel") and then bootstraps
     * its server list through that tunnel. On the dev.034 physical device the announcement
     * measured 14.5s after the carrier opened on a fast link and exceeded the flat 30s
     * window across consecutive attempts under load, with the chain confirmed alive after
     * the gate expired; a slow carrier alone can consume the MASQUE startup budget before
     * the helper even starts. The watchdog and the announcement wait both get this
     * allowance, exactly as Tor chains get theirs.
     */
    private static final long MASQUE_CHAIN_STARTUP_ALLOWANCE_MS = 120_000L;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    /** One extra full connect attempt after a clean teardown; see {@link #retryableConnectFailure}. */
    private static final int CONNECT_ATTEMPTS = 2;
    /**
     * Hard upper bound on the initial connect. Every wait inside the pipeline is individually
     * bounded, but a watchdog is still required because a single wedged native call would
     * otherwise leave the UI in "Connecting" with no terminal state. Sized above the sum of the
     * bounded stages so it only fires on genuinely stuck sessions, never on a slow-but-working one.
     */
    private static final long CONNECT_WATCHDOG_MS = 95_000L;
    private static final long SMART_CONNECT_WATCHDOG_MS = 170_000L;
    /** A leftover core from a previous session must release the SOCKS port before a new one binds. */
    private static final long SOCKS_PORT_RELEASE_TIMEOUT_MS = 3_000L;
    /**
     * Upper bound on the HEV bridge stop. {@code TProxyStopService()} signals quit and then joins the
     * tunnel worker, so from Java it is a native call of unbounded duration - the same category the
     * comment above {@code Builder.establish()} forbids under runtimeLock. A healthy stop returns in
     * single-digit milliseconds, so this bound only ever expires on the path that is a hard hang
     * today: teardown blocked forever, TUN left up, core left alive, every later Connect queued
     * behind it on the single lifecycle thread.
     */
    private static final long BRIDGE_STOP_TIMEOUT_MS = 4_000L;
    /**
     * How long a new session waits for a previous, overrunning bridge stop to finish. Upstream
     * {@code TProxyStartService} is a no-op while a work thread still exists, so starting a bridge
     * over one that is still joining would publish a tunnel that silently carries nothing.
     */
    private static final long BRIDGE_HANDOVER_TIMEOUT_MS = 6_000L;
    /**
     * Each half of the core stop: a polite destroy is given this long, then a forcible one. Unchanged
     * from the two literals it replaces; named so the teardown bound can be stated and tested.
     */
    private static final long CORE_STOP_GRACE_MS = 750L;
    /** Worst case for {@link #stopAetherOnly()}: polite destroy, then forcible destroy. */
    private static final long CORE_STOP_WORST_CASE_MS = CORE_STOP_GRACE_MS * 2;
    private static final long CONNECT_RETRY_DELAY_MS = 600L;
    private static final int DEFAULT_MTU = 1360;
    private static final int MIN_MTU = 1280;
    private static final int MAX_MTU = 1500;
    // A probe has to complete a SOCKS5 handshake, a TLS handshake and an HTTP round trip through the
    // core's upstream. On a nested WARP path the measured endpoint RTT alone is 350-450ms, so TLS
    // (two round trips) plus the request already costs well over a second: at the old 1400ms every
    // candidate failed with "Read timed out" on a link that was in fact perfectly healthy, which
    // wasted 4.5s of connect time and proved nothing. The budget is now sized for that path, and
    // inconclusive failures abort the remaining candidates instead of repeating the same wait.
    private static final int MTU_PROBE_TIMEOUT_MS = 3_500;
    /**
     * Hard stop for the whole automatic selection. A passing probe costs 400-900ms on this path, so
     * the common single-probe case finishes well inside this budget; the budget exists so that a
     * pathological link cannot turn MTU selection into a visible connect delay. Exhausting it keeps
     * whatever has already been proven, or falls back to {@link #DEFAULT_MTU}.
     */
    private static final long AUTOMATIC_MTU_BUDGET_MS = 4_000L;
    private static final int[] MTU_CANDIDATES = {1500, 1450, 1400, 1360, 1320, 1280};
    private static final Map<String, Integer> AUTOMATIC_MTU_CACHE = new ConcurrentHashMap<>();
    // Leave room for transport encapsulation before packets reach the physical link.
    // This is especially important for gool, where the inner WireGuard packet is
    // carried through a second WireGuard tunnel.
    private static final int MASQUE_MTU_CAP = 1400;
    private static final int WIREGUARD_MTU_CAP = 1420;
    private static final int NESTED_WIREGUARD_MTU_CAP = 1360;
    private static final long HEALTH_CHECK_INTERVAL_MS = 30_000L;
    private static final long MAX_HEALTH_VERIFICATION_GAP_MS = 120_000L;
    private static final long LOCATION_PRIMARY_BACKOFF_MS = TimeUnit.HOURS.toMillis(1);
    private static final String LOCATION_CACHE_VERSION_KEY = "locationCacheVersion";
    private static final String LOCATION_CACHE_VALUE_KEY = "locationCacheValue";
    private static final String LOCATION_CACHE_IP_KEY = "locationCacheIp";
    private static final String LOCATION_CACHE_COUNTRY_KEY = "locationCacheCountry";
    private static final String LOCATION_CACHE_TIME_KEY = "locationCacheTime";
    private static final int LOCATION_CACHE_VERSION = 1;
    private static final long NETWORK_RECOVERY_DEBOUNCE_MS = 3_000L;
    private static final String TAG = "AetherVpnService";

    /**
     * Live published state, or {@code null} while no service instance exists. Every Aethon UI
     * surface runs in this same process, so the Quick Settings tile can read the real state here
     * instead of a SharedPreferences value that survives process death and goes stale.
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> LIVE_STATE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** @return the live published state, or an empty string when no service instance is running. */
    static String liveState() {
        String state = LIVE_STATE.get();
        return state == null ? "" : state;
    }

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService telemetry = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean();
    private final AtomicLong locationLookupSequence = new AtomicLong();
    private final ReadinessTrace readinessTrace = new ReadinessTrace();
    private volatile ProbeCancellation[] activeTrafficProbes;
    private volatile ProbeCancellation activePrivacyHealthProbe;
    private volatile RuntimeTrafficPlan.Announcements privacyAnnouncements;
    /** Public 1818/1819 relays that serve the published contract while Chain is the final egress. */
    private PublicPortRelays publicRelays;
    private final AtomicBoolean recoveryRestartPending = new AtomicBoolean();
    private final AtomicBoolean connectWatchdogFired = new AtomicBoolean();
    private final Object runtimeLock = new Object();
    private final Object networkLock = new Object();
    private final Object logLock = new Object();
    private final StringBuilder logHistory = new StringBuilder();
    private final StringBuilder pendingLogs = new StringBuilder();
    private volatile Process aetherProcess;
    private volatile ProxyMode.Listeners proxyListeners;
    private volatile ParcelFileDescriptor vpnInterface;
    private volatile boolean bridgeStarted;
    /** A bridge stop that exceeded {@link #BRIDGE_STOP_TIMEOUT_MS} and is still joining. */
    private volatile Thread pendingBridgeStop;
    private volatile boolean lanSharingActive;
    private volatile boolean stopping = true;
    private volatile boolean active;
    private volatile boolean killSwitch;
    private volatile boolean smartBenchmarking;
    private volatile boolean masqueH3GatewayUnavailable;
    /**
     * dev.034 strict Gool topology truth: the topology the RUNNING v2.3.0 Core actually
     * established, derived only from the Core's own proof lines -
     * "gool ready: masque {peer} carries wireguard {inner}" (Gool over MASQUE) or
     * "establishing inner WARP tunnel (warp-in-warp)" (Classic). null until the Core
     * proves one. A manual mode selection can never be silently overridden: the app maps
     * the WiW endpoint variables only for Classic, and a preset AETHER_GOOL_MODE=classic
     * only for Classic, so the Core has no path to fall back between the two.
     */
    private volatile String runtimeGoolTopology;
    /** The exact Core line that proved the running Gool topology (disclosed in the log). */
    private volatile String runtimeGoolTopologyProof;
    /** Elapsed-realtime instant at which the initial connect must have reached a terminal state. */
    private volatile long connectDeadlineAt;
    private volatile long connectDeadlineSession = -1L;
    /** Session whose connect the watchdog aborted; makes the failure explicit instead of silent. */
    private volatile long connectTimedOutSession = -1L;
    private volatile String currentState = "disconnected";
    private volatile String currentMessage = "Ready to connect";
    private volatile String currentEndpoint = "";
    private volatile String selectedProtocol = "";
    private volatile boolean smartSelected;
    private volatile long connectedAt;
    private volatile long lastLogPersistedAt;
    private volatile long lastHealthCheckAt;
    private volatile long lastSuccessfulHealthAt;
    private volatile long lastNetworkRecoveryAt;
    private volatile long lastPing = -1;
    private volatile long lastTrafficTx;
    private volatile long lastTrafficRx;
    private volatile long lastTrafficSampleAt;
    private volatile long lastHealthTx;
    private volatile long lastHealthRx;
    private volatile int consecutiveHealthFailures;
    private volatile long lastStatsBroadcastAt;
    private volatile long lastWidgetUpdateAt;
    private volatile Intent activeRequest;
    private final Set<Network> availableNetworks = ConcurrentHashMap.newKeySet();
    private volatile boolean networkUnavailable;
    private volatile boolean connectionEstablished;
    private SharedPreferences stateStore;
    /**
     * dev.020 CHANGE 9: the daily connected-time accounting model. The service owns it because
     * it alone knows the genuine Connected state; the Home UI only renders todaySeconds()
     * once per second. Persisted in its own "connected_time" SharedPreferences (usage state,
     * not a configuration preference - Reset Defaults must not erase it; performResetDefaults
     * clears only the "aether" preferences).
     */
    private ConnectedTimeTracker connectedTime;
    private final LanProxyServer lanProxy = new LanProxyServer();
    private ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            boolean hadNetworks = !availableNetworks.isEmpty();
            boolean added = availableNetworks.add(network);
            boolean recovering = networkUnavailable;
            networkUnavailable = false;
            synchronized (networkLock) { networkLock.notifyAll(); }
            refreshLanBinding();
            if (recovering || (added && hadNetworks)) requestNetworkRecovery();
        }

        @Override public void onLost(Network network) {
            boolean removed = availableNetworks.remove(network);
            if (!active || stopping || !removed) return;
            if (availableNetworks.isEmpty()) {
                networkUnavailable = true;
                updateState("reconnecting", getString(R.string.service_network_lost));
                updateNotification(getString(R.string.service_network_lost));
            } else {
                refreshLanBinding();
                requestNetworkRecovery();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        stateStore = getSharedPreferences("service_state", MODE_PRIVATE);
        connectedTime = new ConnectedTimeTracker(connectedTimeClock(), connectedTimeStore());
        selectedProtocol = stateStore.getString("selectedProtocol", "");
        smartSelected = stateStore.getBoolean("smartSelected", false);
        LIVE_STATE.set(currentState);
        // dev.020 CHANGE 9: reconcile the accounting against the persisted checkpoints BEFORE
        // any event can fire (a desired-connected auto-restart launches straight into
        // "starting", which must not count as connected time until Connected publishes).
        connectedTime.reconcile();
        String savedLogs = stateStore.getString("logs", "");
        if (savedLogs != null) logHistory.append(savedLogs);
        createNotificationChannel();
        connectivityManager = getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
        telemetry.scheduleWithFixedDelay(this::runTelemetryTick, 1, 2, TimeUnit.SECONDS);
        telemetry.scheduleWithFixedDelay(this::runLogFlushTick, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * {@code scheduleWithFixedDelay} cancels a task permanently and silently the first time it
     * throws. {@link #publishStats()} is the only caller of {@link #maybeCheckTunnelHealth()} and of
     * {@link #enforceConnectDeadline()}, so one escaped Throwable - from a widget update, a
     * broadcast, or a rejected worker dispatch - used to take the health probe, the ping value, the
     * traffic stats and the connect watchdog down with it for the rest of the session, while the UI
     * kept showing Connected because nothing was left to publish a state change. A failing tick now
     * costs one tick.
     */
    private void runTelemetryTick() {
        try { publishStats(); }
        catch (Throwable error) { Log.w(TAG, "Stats tick failed; keeping the telemetry loop alive", error); }
    }

    /** Same contract as {@link #runTelemetryTick()}: a failing flush must not end log delivery. */
    private void runLogFlushTick() {
        try { flushLogs(); }
        catch (Throwable error) { Log.w(TAG, "Log flush tick failed; keeping the telemetry loop alive", error); }
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
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_CLEAR_LOGS.equals(action)) {
            synchronized (logLock) {
                logHistory.setLength(0);
                pendingLogs.setLength(0);
            }
            stateStore.edit().remove("logs").apply();
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_SET_LAN.equals(action)) {
            boolean enabled = intent.getBooleanExtra("enabled", false) && active && "connected".equals(currentState);
            String socks = activeRequest == null ? "127.0.0.1:1819" : value(activeRequest, "socks", "127.0.0.1:1819");
            if (enabled) startLanSharing(intent.getIntExtra("port", 18190), socks);
            else stopLanSharing();
            sendStatus(currentState, currentMessage);
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            sendLog("Performance disconnect command_received=" + elapsedSinceRequest(intent) + "ms");
            stateStore.edit().putBoolean("desiredConnected", false).apply();
            generation.incrementAndGet();
            stopping = true;
            updateState("disconnecting", getString(R.string.service_disconnecting));
            lifecycle.execute(() -> stopConnection(true));
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            stateStore.edit().putBoolean("desiredConnected", true).apply();
            Intent request = new Intent(intent);
            request.putExtra("socks", ProxyMode.socksAddress(value(request, "connectionMode", "vpn"),
                    value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT)));
            if (isRedundantStart(request)) {
                sendLog("Connect request ignored; Aethon is already " + currentState + " with the same configuration");
                sendStatus(currentState, currentMessage);
                publishStats();
                return START_STICKY;
            }
            long session = generation.incrementAndGet();
            stopping = true;
            updateState("starting", getString(R.string.service_preparing));
            startForegroundCompat(notification(getString(R.string.service_preparing), false));
            lifecycle.execute(() -> {
                stopConnection(false);
                if (generation.get() != session) return;
                activeRequest = request;
                stopping = false;
                active = true;
                killSwitch = request.getBooleanExtra("killSwitch", false);
                worker.execute(() -> runConnection(request, session));
            });
            return START_STICKY;
        }
        return active ? START_STICKY : START_NOT_STICKY;
    }

    /**
     * A tunnel rebuild is only worthwhile when the requested configuration actually differs.
     * Repeated Connect taps otherwise tear down a healthy session and pay the full handshake again.
     */
    private boolean isRedundantStart(Intent request) {
        if (!active || stopping) return false;
        if (!"connected".equals(currentState) && !isConnectingState(currentState)) return false;
        return sameConfiguration(activeRequest, request);
    }

    static boolean isConnectingState(String state) {
        return "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state)
                || "securing".equals(state) || "reconnecting".equals(state);
    }

    @SuppressWarnings("deprecation")
    static boolean sameConfiguration(Intent current, Intent candidate) {
        Bundle active = current == null ? null : current.getExtras();
        Bundle incoming = candidate == null ? null : candidate.getExtras();
        if (active == null || incoming == null) return false;
        if (!active.keySet().equals(incoming.keySet())) return false;
        // Smart Connect rewrites "protocol" on the live intent once it picks a winner, so the
        // stored value legitimately differs from a freshly built request.
        boolean smart = ConnectionDefaults.SMART_PROTOCOL.equals(active.getString("requestedProtocol", ""))
                && ConnectionDefaults.SMART_PROTOCOL.equals(incoming.getString("requestedProtocol", ""));
        for (String key : active.keySet()) {
            if (configurationKeyIgnored(key)) continue;
            if (smart && "protocol".equals(key)) continue;
            if (!String.valueOf(active.get(key)).equals(String.valueOf(incoming.get(key)))) return false;
        }
        return true;
    }

    static boolean configurationKeyIgnored(String key) { return "requestedAtElapsed".equals(key); }

    /**
     * Runs the connect pipeline with a bounded number of attempts. PROMPT phase 2 requires that
     * "Connecting" always resolves to either Connected or a failure with a reason, so every exit
     * from this method publishes a terminal state; a retryable first failure gets one full
     * teardown-and-retry pass before the error is surfaced.
     */
    private void runConnection(Intent request, long session) {
        boolean smartRequested = usesWarp(request) && (ConnectionDefaults.SMART_PROTOCOL.equals(value(request, "requestedProtocol", ""))
                || ConnectionDefaults.SMART_PROTOCOL.equals(value(request, "protocol", "")));
        String requestedTransport = value(request, "transport", ConnectionDefaults.TRANSPORT);
        for (int attempt = 1; ; attempt++) {
            armConnectDeadline(session, smartRequested);
            long extraStartup = 0L;
            if (usesWarp(request)) {
                extraStartup = Math.max(0, request.getIntExtra("startupSecs", 30) - 30) * 1000L;
                if ("mim".equals(value(request, "protocol", ""))) extraStartup += request.getIntExtra("startupSecs", 30) * 1000L;
                // Chain bootstrap allowances: a full-device Tor chain downloads its consensus
                // through the underlay on first run, and a Psiphon chain on a MASQUE carrier
                // bootstraps its server list through a carrier that can itself consume the
                // MASQUE startup budget. The watchdog and the announcement wait both get the
                // matching allowance; see chainStartupAllowanceMs.
                extraStartup += chainStartupAllowanceMs(value(request, "protocol", ConnectionDefaults.PROTOCOL),
                        ProxyMode.settings(value(request, "connectionMode", "vpn"),
                                AndroidCoreSettings.fromIntent(request)));
            }
            connectDeadlineAt += extraStartup;
            boolean publishedConnected = false;
            try {
                publishedConnected = runConnectionAttempt(request, session, attempt, attempt == 1 && smartRequested);
                clearConnectDeadline(session);
                return;
            } catch (Exception error) {
                clearConnectDeadline(session);
                if (stopping || generation.get() != session) return;
                Log.e(TAG, "Connection failed on attempt " + attempt, error);
                sendLog("Error: " + safeMessage(error));
                boolean retry = attempt < CONNECT_ATTEMPTS && !publishedConnected
                        && connectTimedOutSession != session && retryableConnectFailure(error);
                if (retry) {
                    sendLog("Connect attempt " + attempt + " failed; performing a full cleanup and one controlled retry");
                    updateState("starting", getString(R.string.service_connect_retry));
                    updateNotification(getString(R.string.service_connect_retry));
                    if (!prepareRetry(request, session, requestedTransport)) return;
                    continue;
                }
                publishConnectionFailure(request, session, error);
                return;
            }
        }
    }

    /**
     * A retry is only worth the extra wait when a clean restart can plausibly change the outcome.
     * Configuration mistakes, cancellations, occupied proxy ports, a missing native core,
     * and failures of an already-proven session are deterministic or already-reported, so retrying
     * them only delays the error the user needs to see.
     */
    static boolean retryableConnectFailure(String failureType, String message) {
        if ("InterruptedException".equals(failureType)) return false;
        if ("IllegalArgumentException".equals(failureType)) return false;
        if ("ProxyPortException".equals(failureType)) return false;
        if ("SupervisedSessionException".equals(failureType)) return false;
        return message == null || !message.contains("Aether core is missing");
    }

    private static boolean retryableConnectFailure(Throwable error) {
        if (error == null) return false;
        return retryableConnectFailure(error.getClass().getSimpleName(), error.getMessage());
    }

    /** Full teardown between attempts so the retry never inherits a stale core, port, or TUN. */
    private boolean prepareRetry(Intent request, long session, String requestedTransport) {
        stopRuntime();
        reapOrphanedCores();
        awaitSocksPortReleased(value(request, "socks", "127.0.0.1:1819"));
        masqueH3GatewayUnavailable = false;
        connectionEstablished = false;
        recoveryRestartPending.set(false);
        // The HTTP/3 -> HTTP/2 fallback rewrites this extra in place; the retry must start from the
        // transport the user actually selected.
        request.putExtra("transport", requestedTransport);
        try { Thread.sleep(CONNECT_RETRY_DELAY_MS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        return !stopping && generation.get() == session;
    }

    /** Publishes the single terminal failure state for a session and releases the runtime. */
    private void publishConnectionFailure(Intent request, long session, Exception error) {
        boolean timedOut = connectTimedOutSession == session;
        String reason = timedOut ? getString(R.string.service_connect_timeout)
                : error instanceof ProxyStartupException || error.getCause() instanceof ProxyStartupException
                ? safeMessage(error) : getString(R.string.status_error);
        if (killSwitch && vpnInterface != null && !stopping) {
            stopAetherOnly();
            updateState("blocked", getString(R.string.service_blocked));
            updateNotification(getString(R.string.service_blocked_notification));
            return;
        }
        stopRuntime();
        stateStore.edit().putBoolean("desiredConnected", false).apply();
        updateState("error", reason);
        stopForeground(STOP_FOREGROUND_REMOVE);
        // Kill switch is off, so Android has already restored direct routing. A session that had
        // actually reached Connected must tell the user that traffic left the tunnel, because the
        // foreground notification this failure path removes was the only remaining indicator.
        if (connectedAt > 0) notifyProtectionLost(reason);
        active = false;
        connectionEstablished = false;
        connectedAt = 0;
        // Nothing is left to supervise. onDestroy() only rewrites the published state for states in
        // canDisconnect(), and "error" is deliberately not one of them, so the reason survives.
        stopSelf();
    }

    /**
     * @return true once "connected" has been published for this attempt.
     */
    private boolean runConnectionAttempt(Intent request, long session, int attempt, boolean runSmartSelection) throws Exception {
        long pipelineStarted = SystemClock.elapsedRealtime();
        boolean publishedConnected = false;
        sendLog("Performance connect service_pipeline_start=" + elapsedSinceRequest(request) + "ms attempt=" + attempt);
        currentEndpoint = reliableEndpoint(request);
        String connectionMode = value(request, "connectionMode", "vpn");
        smartSelected = usesWarp(request) && ConnectionDefaults.SMART_PROTOCOL.equals(value(request, "requestedProtocol", ""));
        if (runSmartSelection && usesWarp(request)) {
            updateState("smart-testing", getString(R.string.service_smart_testing));
            String protocol = chooseSmartProtocol(request, session);
            request.putExtra("protocol", protocol);
            stateStore.edit().putString("smartProtocol", protocol).apply();
            selectedProtocol = protocol;
            sendLog("Smart Connect selected " + protocolLabel(protocol));
            sendLog("Performance smart decision=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
            currentEndpoint = reliableEndpoint(request);
        } else selectedProtocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        updateState("starting", getString(R.string.service_launching));
        updateState("scanning", getString(R.string.service_scanning));
        boolean socksReady = startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS, session);
        ensureConnectNotTimedOut(session);
        if (!socksReady) {
            throw new IllegalStateException(aetherExitMessage("Aether did not open its SOCKS5 listener"));
        }
        if (!isCurrentSession(request, session)) return false;
        sendLog("Performance core_and_socks_ready=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");

        if ("automatic".equals(VpnConnectionController.normalizedMtuMode(value(request, "mtuMode", "manual")))) {
            long mtuStarted = SystemClock.elapsedRealtime();
            int selectedMtu = selectAutomaticMtu(request, session);
            request.putExtra("mtu", selectedMtu);
            sendLog("MTU automatic selected=" + selectedMtu + " effective=" + effectiveMtu(request)
                    + " selection=" + (SystemClock.elapsedRealtime() - mtuStarted) + "ms");
        } else {
            request.putExtra("mtu", VpnConnectionController.parseMtu(Integer.toString(request.getIntExtra("mtu", DEFAULT_MTU))));
            sendLog("MTU manual configured=" + request.getIntExtra("mtu", DEFAULT_MTU) + " effective=" + effectiveMtu(request));
        }
        ensureConnectNotTimedOut(session);

        // Never publish a prior session's endpoint while this session is being resolved.
        currentEndpoint = getString(R.string.location_detecting);
        boolean gool = usesWarp(request) && "gool".equals(value(request, "protocol", ConnectionDefaults.PROTOCOL));
        connectionEstablished = true;
        if ("manual".equals(connectionMode)) {
            if (!isCurrentSession(request, session)) return false;
            updateState("securing", getString(R.string.service_traffic_checking));
            if (!establishProvenDataPlane(request, session, pipelineStarted)) return false;
            startPublicRelays(request);
            ensureConnectNotTimedOut(session);
            connectedAt = System.currentTimeMillis();
            updateState("connected", getString(R.string.service_proxy_ready));
            updateNotification(getString(R.string.service_proxy_connected));
            publishedConnected = true;
        } else {
            if (!establishVpn(request, session)) return false;
            sendLog("Performance tun_and_routing_ready=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
            updateState("securing", getString(R.string.service_traffic_checking));
            if (!establishProvenDataPlane(request, session, pipelineStarted)) return false;
            startPublicRelays(request);
            ensureConnectNotTimedOut(session);
            connectedAt = System.currentTimeMillis();
            updateState("connected", getString(R.string.service_protected));
            updateNotification(getString(smartSelected ? R.string.service_smart_protected : R.string.service_aethon_protected));
            publishedConnected = true;
        }
        clearConnectDeadline(session);
        cancelProtectionLostAlert();
        sendLog("Performance connected_published=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
        // The periodic telemetry task intentionally starts with a one-second delay. Run the
        // first health probe as soon as routing is published so the UI does not wait for that
        // scheduler tick; the probe still uses the real SOCKS path and never reuses stale data.
        maybeCheckTunnelHealth();
        if (request.getBooleanExtra("lanEnabled", false)) {
            startLanSharing(request.getIntExtra("lanPort", 18190), value(request, "socks", "127.0.0.1:1819"));
            sendStatus(currentState, currentMessage);
        }
        worker.execute(() -> NetworkDiagnostics.run(this, value(request, "protocol", "masque")));
        if (gool) scheduleGoolExitLookup(request, session, pipelineStarted);
        else scheduleLocationLookup(request, session);
        if (gool) discloseGoolTopology(request);
        try {
            monitorAether(request, session);
        } catch (Exception supervised) {
            // The tunnel was proven working once, so this is a recovery failure rather than a
            // connect failure. Surface it directly; a blind reconnect loop would hide it.
            throw new SupervisedSessionException(safeMessage(supervised), supervised);
        }
        return publishedConnected;
    }

    private void armConnectDeadline(long session, boolean smart) {
        connectWatchdogFired.set(false);
        connectTimedOutSession = -1L;
        connectDeadlineSession = session;
        connectDeadlineAt = SystemClock.elapsedRealtime() + (smart ? SMART_CONNECT_WATCHDOG_MS : CONNECT_WATCHDOG_MS);
    }

    /**
     * dev.034: truthful disclosure of the Gool topology after Connected. The requested mode and
     * the topology the Core proved from its own log lines are stated together; because the app
     * never maps the classic-only variables while Gool over MASQUE is selected (and vice versa),
     * a mismatch would mean the Core fell back on its own - which is surfaced here as the exact
     * mismatch, never as a quiet success of the requested mode.
     */
    private void discloseGoolTopology(Intent request) {
        boolean classicRequested = "classic".equals(CoreSettings.string(AndroidCoreSettings.fromIntent(request), "goolMode"));
        String requested = classicRequested ? "Classic Gool (WARP-in-WARP)" : "Gool over MASQUE";
        String topology = runtimeGoolTopology;
        if (topology == null) {
            sendLog("Gool topology: requested " + requested + "; the Core has not printed its topology proof line");
            return;
        }
        String running = "classic".equals(topology) ? "Classic Gool (WARP-in-WARP)" : "Gool over MASQUE";
        sendLog("Gool topology proof: requested " + requested + ", running " + running
                + " (core: " + runtimeGoolTopologyProof + ")");
        if (!topology.equals(classicRequested ? "classic" : "masque")) {
            // By construction this cannot happen (the Core's gool_classic() is driven only by
            // variables the app maps for the matching mode); if it ever does, the mismatch is
            // disclosed, not buried - a fallback result can never verify the requested topology.
            sendLog("Gool topology MISMATCH: the Core is not running the requested topology");
        }
    }

    private void clearConnectDeadline(long session) {
        if (connectDeadlineSession == session) {
            connectDeadlineAt = 0L;
            connectDeadlineSession = -1L;
        }
    }

    /** Only the initial connect is watchdogged; recovery legitimately waits for the network. */
    static boolean initialConnectState(String state) {
        return "starting".equals(state) || "smart-testing".equals(state)
                || "scanning".equals(state) || "securing".equals(state);
    }

    /**
     * Runs from the telemetry scheduler. Turns a wedged connect into a terminal failure by tearing
     * the runtime down, which unblocks every socket wait in the pipeline so the normal error path
     * publishes a reason.
     */
    private void enforceConnectDeadline() {
        long deadline = connectDeadlineAt;
        long session = connectDeadlineSession;
        if (deadline == 0L || session < 0L) return;
        if (stopping || !active || generation.get() != session || !initialConnectState(currentState)) {
            clearConnectDeadline(session);
            return;
        }
        if (SystemClock.elapsedRealtime() < deadline) return;
        if (!connectWatchdogFired.compareAndSet(false, true)) return;
        connectDeadlineAt = 0L;
        connectTimedOutSession = session;
        sendLog("Connect watchdog expired in state=" + currentState + "; aborting the stuck connect instead of waiting");
        worker.execute(() -> {
            if (generation.get() != session) return;
            stopRuntime();
        });
    }

    private void ensureConnectNotTimedOut(long session) {
        if (connectTimedOutSession == session) {
            throw new IllegalStateException(getString(R.string.service_connect_timeout));
        }
    }

    /** Marks a failure that happened after the tunnel was already proven, so no retry applies. */
    private static final class SupervisedSessionException extends Exception {
        SupervisedSessionException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Proves that the established Aether path can complete a real HTTPS request before exposing
     * the connected state. This uses the same SOCKS5 data plane that HEV forwards for applications
     * and is bounded so a broken gateway becomes an explicit connection error instead of a false
     * success or an indefinitely hanging startup.
     *
     * <p>Each target exercises the whole chain end to end: the hostname is handed to the core as a
     * SOCKS5 domain request (ATYP=3), so name resolution happens inside the tunnel, then TCP,
     * a verified TLS handshake with SNI, and a parsed HTTP response all have to succeed. The
     * targets sit behind three independent operators so one unreachable endpoint cannot fail a
     * healthy tunnel.
     */
    private static final String[][] TRAFFIC_READY_TARGETS = {
            {"example.com", "/"},
            {"www.cloudflare.com", "/cdn-cgi/trace"},
            // www.msftconnecttest.com used to be the third target and was never once able to succeed:
            // it serves a certificate that does not match its own name, so the verified handshake this
            // gate requires always failed. The device logged
            // "Real traffic validation attempt 1/2 host=www.msftconnecttest.com No subjectAltNames on
            // the certificate match" on every connect, and curl from the workstation - outside the
            // tunnel entirely - reproduces it as SEC_E_WRONG_PRINCIPAL, so it is a property of the
            // endpoint and not of the tunnel. Racing a target that can only ever lose meant the real
            // redundancy was two operators, not three. detectportal.firefox.com is purpose-built for
            // connectivity checks, serves a valid certificate for its own name and an 8-byte body, and
            // sits on a fourth network (Fastly) so the three targets remain independent.
            {"detectportal.firefox.com", "/success.txt"},
    };

    /** The traffic-gate hosts, exposed so a test can keep them independent and individually usable. */
    static String[] trafficReadyHosts() {
        String[] hosts = new String[TRAFFIC_READY_TARGETS.length];
        for (int index = 0; index < hosts.length; index++) hosts[index] = TRAFFIC_READY_TARGETS[index][0];
        return hosts;
    }

    /**
     * Proves that real traffic traverses the tunnel before Connected is published.
     *
     * @return {@code true} when a target was actually proven. {@code false} means the session was
     *         superseded or cancelled while validating, so nothing was proven and the caller must
     *         abandon the pipeline. Returning void here used to make that case indistinguishable
     *         from success: a disconnect arriving mid-validation returned early and the caller went
     *         straight on to publish Connected. On the device that produced
     *         "disconnect teardown=200ms" followed 5ms later by "connected_published=9606ms" - a
     *         Connected state for a tunnel that had already been torn down.
     * @throws Exception when every target failed on every attempt
     */
    /**
     * Proves the data plane before "connected" is published, re-rolling the core's endpoint choice
     * in place instead of tearing the VPN down.
     *
     * <p>The gate itself is unchanged: real HTTPS through the real SOCKS path still has to succeed,
     * so a tunnel carrying zero bytes can never reach the connected state. What changes is the
     * response to a failure. A failure means the core handed over an endpoint pair that cannot carry
     * traffic; the core, tun0, routing and the HEV bridge are all healthy. Restarting only the core
     * makes it pick a new pair, which is the actual cure, and costs roughly a core start plus one
     * probe round instead of the full {@link #prepareRetry} teardown.
     *
     * <p>A full reconnect is reserved for the cases that a re-roll cannot fix: the core process is
     * gone, its listener no longer accepts SOCKS, MASQUE has no reachable gateway at all, or the
     * roll and time budgets are spent. Those propagate the original failure to the caller unchanged.
     *
     * @return false when the session was superseded mid-flight; true when real traffic is proven
     * @throws Exception when the tunnel is broken rather than merely holding a bad endpoint
     */
    private boolean establishProvenDataPlane(Intent request, long session, long pipelineStarted)
            throws Exception {
        if (!usesWarp(request))
            return validateTrafficReady(request, session, pipelineStarted, TRAFFIC_READY_ATTEMPTS);
        long deadline = trafficGateDeadline(session);
        Exception unproven = null;
        for (int roll = 0; roll <= TRAFFIC_READY_ENDPOINT_ROLLS; roll++) {
            ensureConnectNotTimedOut(session);
            if (!isCurrentSession(request, session)) return false;
            try {
                return validateTrafficReady(request, session, pipelineStarted,
                        roll == 0 ? TRAFFIC_READY_ATTEMPTS : ROLL_TRAFFIC_READY_ATTEMPTS);
            } catch (Exception failed) {
                unproven = failed;
            }
            if (roll == TRAFFIC_READY_ENDPOINT_ROLLS) {
                sendLog("Traffic gate exhausted " + TRAFFIC_READY_ENDPOINT_ROLLS
                        + " endpoint re-rolls; falling back to a full reconnect");
                break;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining < MIN_TRAFFIC_READY_ROLL_BUDGET_MS) {
                sendLog("Traffic gate has " + Math.max(0L, remaining) + "ms left after " + roll
                        + " re-roll(s), too little for another; falling back to a full reconnect");
                break;
            }
            if (!isCurrentSession(request, session)) return false;
            String diagnosis = diagnoseTunnel(request);
            if (diagnosis != null) {
                sendLog("Traffic gate will not re-roll: " + diagnosis);
                break;
            }
            // A privacy helper that is still bootstrapping (its listeners not yet announced) is
            // killed by a core restart, and the replacement has to start its own bootstrap from
            // scratch — the September 30 dev.014 validation reproduced exactly that loop: five
            // re-rolls, each one killing the psiphon helper a few seconds before it would have
            // announced. While announcements are outstanding, a re-roll cannot be the cure, so
            // the original privacy failure propagates instead of restarting the core.
            RuntimeTrafficPlan.Announcements announcements = privacyAnnouncements;
            if (announcements != null && !announcements.allAnnounced()) {
                sendLog("Traffic gate will not re-roll: privacy helper is still bootstrapping"
                        + " (" + safeMessage(unproven) + "); waiting cannot be fixed by a core restart");
                break;
            }
            sendLog("Traffic gate re-roll " + (roll + 1) + "/" + TRAFFIC_READY_ENDPOINT_ROLLS
                    + ": core is alive and SOCKS is accepting, so the endpoint pair is at fault"
                    + " (" + safeMessage(unproven) + "); restarting the core in place with "
                    + remaining + "ms left");
            updateState("securing", getString(R.string.service_testing_gateways));
            stopAetherOnly();
            awaitSocksPortReleased(value(request, "socks", "127.0.0.1:1819"));
            sleepQuietly(TRAFFIC_READY_ROLL_SETTLE_MS);
            ensureConnectNotTimedOut(session);
            if (!isCurrentSession(request, session)) return false;
            // Keep enough of the budget for the probe round that has to follow the restart, so the
            // gate never spends its last second waiting on a listener it will not get to test.
            long socksWait = Math.min(ROLL_SOCKS_TIMEOUT_MS,
                    deadline - SystemClock.elapsedRealtime() - TRAFFIC_READY_TIMEOUT_MS);
            if (socksWait <= 0L || !startAetherWithMasqueFallback(request, socksWait, session)) {
                sendLog("Re-rolled core did not open its SOCKS listener within "
                        + Math.max(0L, socksWait) + "ms");
                break;
            }
            sendLog("Performance traffic_gate_reroll=" + (roll + 1) + " core_ready="
                    + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
            updateState("securing", getString(R.string.service_traffic_checking));
        }
        throw unproven != null ? unproven
                : new IllegalStateException("Real traffic validation failed");
    }

    /**
     * Names the reason a tunnel is broken rather than merely holding an endpoint that cannot carry
     * traffic, or returns null when re-rolling the endpoint is still the right response.
     */
    private String diagnoseTunnel(Intent request) {
        Process process = aetherProcess;
        if (process == null) return "the core process is gone";
        if (!process.isAlive()) return "the core process exited with status " + exitStatus(process);
        if (masqueH3GatewayUnavailable) return "MASQUE reported no usable gateway";
        String socks = value(request, "socks", "127.0.0.1:1819");
        try {
            if (!socksHandshakeSucceeds(HostPort.parse(socks))) {
                return "the core stopped accepting SOCKS5 on " + socks;
            }
        } catch (IllegalArgumentException invalid) {
            return "the configured SOCKS5 address " + socks + " is unusable";
        }
        return null;
    }

    private static String exitStatus(Process process) {
        try { return Integer.toString(process.exitValue()); }
        catch (IllegalThreadStateException stillRunning) { return "unknown"; }
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /**
     * When the traffic gate must stop re-rolling: its own budget, or the connect watchdog less a
     * margin, whichever comes first. Deriving it from the live watchdog deadline rather than from
     * fixed arithmetic is what keeps the gate honest when the gateway scan before it was slow — the
     * gate then gets less time, reports its own reason, and leaves the watchdog unused instead of
     * being cut off mid-probe by a timeout that explains nothing.
     */
    private long trafficGateDeadline(long session) {
        long budget = SystemClock.elapsedRealtime() + TRAFFIC_READY_TOTAL_BUDGET_MS;
        long armed = connectDeadlineAt;
        if (connectDeadlineSession != session || armed <= 0L) return budget;
        return Math.min(budget, armed - TRAFFIC_READY_WATCHDOG_MARGIN_MS);
    }

    /**
     * Static ceiling on the gate: its own budget plus the probe round it always reserves room for.
     * The live deadline can only be earlier than this. Tests assert the ceiling itself stays inside
     * the connect watchdog, because a gate that could outlive the watchdog turns a recoverable bad
     * endpoint back into the abrupt failure this fix exists to remove.
     */
    static long worstCaseTrafficGateMs() {
        return TRAFFIC_READY_TOTAL_BUDGET_MS
                + Math.max(TRAFFIC_READY_TIMEOUT_MS, CHAIN_TRAFFIC_READY_TIMEOUT_MS);
    }

    /** Probe budgets published for tests: {WARP-direct, privacy-chain}. */
    static int[] trafficProbeTimeouts() {
        return new int[]{TRAFFIC_READY_TIMEOUT_MS, CHAIN_TRAFFIC_READY_TIMEOUT_MS};
    }

    /** Bounds published for tests: {rolls, first-gate attempts, per-roll attempts}. */
    static int[] trafficGateBounds() {
        return new int[]{TRAFFIC_READY_ENDPOINT_ROLLS, TRAFFIC_READY_ATTEMPTS, ROLL_TRAFFIC_READY_ATTEMPTS};
    }

    /** Timings published for tests: {watchdog margin, per-roll SOCKS wait, minimum roll budget}. */
    static long[] trafficGateTimings() {
        return new long[]{TRAFFIC_READY_WATCHDOG_MARGIN_MS, ROLL_SOCKS_TIMEOUT_MS,
                MIN_TRAFFIC_READY_ROLL_BUDGET_MS};
    }

    static long connectWatchdogMs() { return CONNECT_WATCHDOG_MS; }

    /** Bounds published for tests: {bridge stop wait, bridge handover wait, core stop worst case}. */
    static long[] teardownTimings() {
        return new long[]{BRIDGE_STOP_TIMEOUT_MS, BRIDGE_HANDOVER_TIMEOUT_MS, CORE_STOP_WORST_CASE_MS};
    }

    /**
     * Waits, bounded by {@link #PRIVACY_ANNOUNCEMENT_TIMEOUT_MS}, for the current Core to announce
     * every privacy listener. Returns false when the session was superseded. The wait is on Core's
     * own readiness line, so a helper that cannot establish a tunnel still turns into an honest
     * gate failure with a reason ("privacy listeners were not announced") rather than a probe
     * error that invites a core restart — restarting cannot help a bootstrapping helper.
     */
    private boolean awaitPrivacyAnnouncements(Intent request, long session) {
        RuntimeTrafficPlan.Announcements announcements = privacyAnnouncements;
        if (announcements == null) return isCurrentSession(request, session);
        long timeout = privacyAnnouncementTimeoutMs(value(request, "protocol", ConnectionDefaults.PROTOCOL),
                ProxyMode.settings(value(request, "connectionMode", "vpn"),
                        AndroidCoreSettings.fromIntent(request)));
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!announcements.allAnnounced()) {
            if (!isCurrentSession(request, session)) return false;
            Process process = aetherProcess;
            if (process == null || !process.isAlive()) {
                throw new IllegalStateException("Core exited before announcing its privacy listeners");
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new IllegalStateException("Psiphon/Tor privacy listeners were not announced by the Core within "
                        + timeout + "ms; the helper could not establish its tunnel");
            }
            sleepQuietly(250);
        }
        return isCurrentSession(request, session);
    }

    /**
     * Startup allowance for a full-device privacy chain whose helper bootstraps through a
     * carrier that is itself slow to establish. Tor chains download the consensus through the
     * underlay (120s); a Psiphon chain on a MASQUE carrier bootstraps its server list through
     * the carrier tunnel, which on a slow H2 network is the difference between a bounded,
     * honest wait and a first-attempt failure that the dev.034 physical validation reproduced.
     * Plain WARP-based chains keep the base window: the fast underlay leaves the helper the
     * full 30s.
     *
     * <p>The protocol is a parameter, never read from {@code settings}: the maps produced by
     * {@link AndroidCoreSettings#fromIntent} carry only keys listed in
     * {@code CoreSettings.DEFAULTS}, which deliberately does not include {@code protocol} (the
     * service intent's protocol string travels beside the map, exactly as every other
     * protocol-dependent call site passes it). A dev.035 draft of this helper read the protocol
     * from the map, so the allowance never applied and the gate stayed at 30s; the physical
     * re-validation caught it.
     */
    static long chainStartupAllowanceMs(String protocol, Map<String, Object> settings) {
        if (TorChainRouting.chainActive(settings)) return TOR_CHAIN_STARTUP_ALLOWANCE_MS;
        if (PsiphonChainRouting.chainActive(settings)
                && CoreSettings.masqueCarrier(protocol, settings))
            return MASQUE_CHAIN_STARTUP_ALLOWANCE_MS;
        return 0L;
    }

    /** The privacy-announcement window for a chain: base timeout plus its startup allowance. */
    static long privacyAnnouncementTimeoutMs(String protocol, Map<String, Object> settings) {
        return PRIVACY_ANNOUNCEMENT_TIMEOUT_MS + chainStartupAllowanceMs(protocol, settings);
    }

    /** Bounds published for tests: {base timeout, Tor allowance, MASQUE-carrier allowance}. */
    static long[] privacyAnnouncementBounds() {
        return new long[]{PRIVACY_ANNOUNCEMENT_TIMEOUT_MS, TOR_CHAIN_STARTUP_ALLOWANCE_MS,
                MASQUE_CHAIN_STARTUP_ALLOWANCE_MS};
    }

    private boolean validateTrafficReady(Intent request, long session, long pipelineStarted, int attempts) throws Exception {
        String socks = value(request, "socks", "127.0.0.1:1819");
        java.util.List<RuntimeTrafficPlan.Endpoint> endpoints = RuntimeTrafficPlan.endpoints(
                ProxyMode.settings(value(request, "connectionMode", "vpn"), AndroidCoreSettings.fromIntent(request)), socks);
        // Privacy listeners (Psiphon/Tor side proxies) only bind once their helper established a
        // tunnel, which Core announces in its log ("psiphon is ready; ..."). Probing them before
        // that announcement is ECONNREFUSED by design, not a broken data plane, so wait for the
        // announcement here instead of letting the probe round fail into a core restart that
        // would kill the bootstrapping helper. WARP endpoints announce nothing and skip the wait.
        if (!awaitPrivacyAnnouncements(request, session)) return false;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!isCurrentSession(request, session)) return false;
            Process provingProcess = aetherProcess;
            RuntimeTrafficPlan.Announcements announcements = privacyAnnouncements;
            RuntimeTrafficPlan.Proof proof = new RuntimeTrafficPlan.Proof(endpoints);
            // The targets are independent operators, so they are raced instead of tried in sequence.
            // Serially, one blocked or black-holed target spent the whole per-target timeout before
            // the next was even attempted, so an attempt could cost three timeouts on the critical
            // path; raced, an attempt costs one, and the first proven target publishes Connected.
            CompletionService<TrafficResult> race = new ExecutorCompletionService<>(worker);
            ProbeCancellation[] probes = new ProbeCancellation[endpoints.size()];
            for (int index = 0; index < probes.length; index++) probes[index] = new ProbeCancellation();
            activeTrafficProbes = probes;
            try {
                for (int index = 0; index < endpoints.size(); index++) {
                  final int endpointIndex = index;
                  final RuntimeTrafficPlan.Endpoint endpoint = endpoints.get(index);
                  final ProbeCancellation cancellation = probes[index];
                    for (String[] target : TRAFFIC_READY_TARGETS) {
                    final String host = target[0];
                    final String path = target[1];
                    // dev.037: a privacy listener whose helper just bootstrapped gets the
                    // chain probe budget - see CHAIN_TRAFFIC_READY_TIMEOUT_MS.
                    final int probeTimeoutMs = endpoint.privacy
                            ? CHAIN_TRAFFIC_READY_TIMEOUT_MS : TRAFFIC_READY_TIMEOUT_MS;
                    cancellation.track(race.submit(() -> {
                        long started = SystemClock.elapsedRealtime();
                        try {
                            String body = proxyHttpGet(endpoint.address,
                                    host, path, probeTimeoutMs, endpoint.http, cancellation);
                            if (body.trim().isEmpty()) throw new IllegalStateException("HTTPS response body was empty");
                            return new TrafficResult(endpointIndex, host, body, SystemClock.elapsedRealtime() - started);
                        } catch (Exception error) {
                            throw new IllegalStateException(endpoint.name
                                    + " host=" + host + " " + safeMessage(error), error);
                        }
                    }));
                  }
                }
                for (int completed = 0; completed < TRAFFIC_READY_TARGETS.length * endpoints.size(); completed++) {
                    try {
                        TrafficResult proven = race.take().get();
                        if (!isCurrentSession(request, session)) return false;
                        if (provingProcess == null || provingProcess != aetherProcess || !provingProcess.isAlive()) {
                            throw new IllegalStateException("Core changed during traffic validation");
                        }
                        RuntimeTrafficPlan.Endpoint endpoint = endpoints.get(proven.endpointIndex);
                        if (announcements == null || announcements != privacyAnnouncements || !announcements.announced(endpoint)) {
                            last = new IllegalStateException(endpoint.name + " was not announced by the current Core");
                            continue;
                        }
                        if (endpoint.primary && "www.cloudflare.com".equals(proven.host)) {
                            readinessTrace.remember(provingProcess, session, SystemClock.elapsedRealtime(), proven.body);
                        }
                        // A successful protocol no longer needs its other targets. Interrupting a
                        // Future alone cannot unblock a socket read, so close its sockets as well.
                        probes[proven.endpointIndex].close();
                        sendLog("Performance traffic_ready attempt=" + attempt + " "
                                + endpoint.name + " host=" + proven.host
                                + " latency=" + proven.latencyMs + "ms"
                                + " total=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
                        if (proof.record(endpoint)) {
                            if (!announcements.allAnnounced()) continue;
                            if (!isCurrentSession(request, session)) return false;
                            if (provingProcess == null || provingProcess != aetherProcess || !provingProcess.isAlive()) {
                                throw new IllegalStateException("Core changed during traffic validation");
                            }
                            return true;
                        }
                    } catch (CancellationException cancelled) {
                        if (!isCurrentSession(request, session)) return false;
                    } catch (ExecutionException failed) {
                        Throwable cause = failed.getCause();
                        last = cause instanceof Exception ? (Exception) cause
                                : new IllegalStateException(safeMessage(cause));
                        sendLog("Real traffic validation attempt " + attempt + "/" + attempts
                                + " " + safeMessage(last));
                    }
                }
            } finally {
                for (ProbeCancellation probe : probes) probe.close();
                if (activeTrafficProbes == probes) activeTrafficProbes = null;
            }
        }
        throw new IllegalStateException("Real traffic validation failed", last);
    }

    private static final class TrafficResult {
        final int endpointIndex;
        final String host, body;
        final long latencyMs;
        TrafficResult(int endpointIndex, String host, String body, long latencyMs) {
            this.endpointIndex = endpointIndex; this.host = host; this.body = body; this.latencyMs = latencyMs;
        }
    }

    /**
     * Automatic MTU selection. Two independent mechanisms are combined because an unprivileged
     * Android app cannot set IP_DONTFRAG / DF on its sockets and therefore cannot run a real
     * per-candidate path-MTU discovery:
     *
     * <ol>
     *   <li>{@link #automaticMtuCeiling} does the arithmetic that actually prevents fragmentation:
     *       the candidate must fit inside the measured link MTU after the transport's own
     *       encapsulation overhead, and inside the protocol's hard ceiling.</li>
     *   <li>{@link #probeMtuCandidate} then proves that a payload of at least the candidate size
     *       really traverses the core's upstream in both directions, which catches a gateway that
     *       silently black-holes full-size segments.</li>
     * </ol>
     *
     * Candidates are searched rather than walked. The ceiling arithmetic has already discarded every
     * value that cannot fit, so the largest survivor is the one most likely to pass and is probed
     * first - when it passes, which is the common case, the whole selection costs a single probe. A
     * size failure then narrows the range and the remainder is a binary search, so six candidates
     * need at most four probes instead of six. A proven value is cached per network+protocol, and a
     * total budget guarantees the selection can never become a visible connect delay. If nothing can
     * be proven the selection falls back to the conservative 1360 default rather than guessing.
     */
    private int selectAutomaticMtu(Intent request, long session) {
        long started = SystemClock.elapsedRealtime();
        String protocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        int linkMtu = activeLinkMtu();
        String key = automaticMtuCacheKey(protocol, linkMtu);
        Integer cached = AUTOMATIC_MTU_CACHE.get(key);
        if (cached != null) {
            sendLog("MTU automatic reused proven value=" + cached + " for " + key + " probes=0"
                    + " selection=" + (SystemClock.elapsedRealtime() - started) + "ms");
            return effectiveMtu(protocol, cached);
        }
        int ceiling = automaticMtuCeiling(protocol, linkMtu);
        int[] usable = usableMtuCandidates(ceiling);
        sendLog("MTU automatic probing protocol=" + protocol + " link_mtu=" + (linkMtu > 0 ? linkMtu : -1)
                + " ceiling=" + ceiling + " candidates=" + Arrays.toString(usable));
        String socks = value(request, "socks", "127.0.0.1:1819");
        int lo = 0;
        int hi = usable.length - 1;
        int best = -1;
        int probes = 0;
        boolean top = true;
        while (lo <= hi) {
            // A cancelled or superseded session must abandon the search, not keep probing on behalf
            // of a session that no longer exists.
            if (!isCurrentSession(request, session)) return DEFAULT_MTU;
            long spent = SystemClock.elapsedRealtime() - started;
            if (spent > AUTOMATIC_MTU_BUDGET_MS) {
                sendLog("MTU automatic budget exhausted spent=" + spent + "ms probes=" + probes);
                break;
            }
            int index = top ? hi : lo + (hi - lo) / 2;
            top = false;
            int candidate = usable[index];
            probes++;
            try {
                long bytes = probeMtuCandidate(socks, candidate, MTU_PROBE_TIMEOUT_MS);
                sendLog("MTU probe passed candidate=" + candidate + " transferred=" + bytes + "B");
                best = index;
                lo = index + 1;
            } catch (Exception error) {
                // Only a truncated response is evidence about MTU: it means full-size segments are
                // being dropped, so smaller candidates are worth searching. A transport-level
                // failure (timeout, reset, closed connection) says nothing about packet size - it
                // means the path was too slow or momentarily unavailable - and every remaining
                // candidate would hit exactly the same wall. Continuing there only delays the
                // connect, so the search stops and whatever is already proven is used instead.
                boolean aboutSize = mtuProbeFailureIsAboutSize(safeMessage(error));
                sendLog("MTU probe " + (aboutSize ? "failed" : "inconclusive") + " candidate=" + candidate
                        + " reason=" + safeMessage(error));
                if (!aboutSize) break;
                hi = index - 1;
            }
        }
        long selection = SystemClock.elapsedRealtime() - started;
        if (best >= 0) {
            AUTOMATIC_MTU_CACHE.put(key, usable[best]);
            sendLog("MTU automatic selected=" + usable[best] + " probes=" + probes
                    + " selection=" + selection + "ms");
            return usable[best];
        }
        sendLog("MTU automatic could not prove any candidate probes=" + probes
                + "; using the safe default " + DEFAULT_MTU + " selection=" + selection + "ms");
        return DEFAULT_MTU;
    }

    /**
     * @return the automatic-mode candidates that fit under {@code ceiling}, ascending so a binary
     *         search can run over them. Never empty: {@link #automaticMtuCeiling} floors at
     *         {@link #MIN_MTU}, which is itself the smallest candidate.
     */
    static int[] usableMtuCandidates(int ceiling) {
        int[] out = new int[MTU_CANDIDATES.length];
        int count = 0;
        for (int index = MTU_CANDIDATES.length - 1; index >= 0; index--) {
            if (MTU_CANDIDATES[index] <= ceiling) out[count++] = MTU_CANDIDATES[index];
        }
        return Arrays.copyOf(out, count);
    }

    /**
     * @return whether a probe failure carries information about packet size. A truncated response is
     *         the signature of a path that drops full-size segments; anything else (timeout, reset,
     *         closed connection, TLS failure) is a transport problem that would repeat identically
     *         for every smaller candidate.
     */
    static boolean mtuProbeFailureIsAboutSize(String reason) {
        return reason != null && reason.contains("truncated");
    }

    /**
     * Largest MTU that cannot fragment on this path. {@code MAX_MTU - protocolCap} is the
     * transport's per-packet encapsulation overhead (MASQUE 100, WireGuard 80, nested WireGuard
     * 140), so a link smaller than Ethernet shrinks the ceiling by exactly that amount.
     */
    static int automaticMtuCeiling(String protocol, int linkMtu) {
        int protocolCap = effectiveMtu(protocol, MAX_MTU);
        if (linkMtu < MIN_MTU) return protocolCap;
        int overhead = MAX_MTU - protocolCap;
        return Math.max(MIN_MTU, Math.min(protocolCap, linkMtu - overhead));
    }

    /** @return the automatic-mode probe order, largest first. */
    static int[] mtuCandidates() { return MTU_CANDIDATES.clone(); }

    private int activeLinkMtu() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null || Build.VERSION.SDK_INT < 29) return 0;
        List<Network> candidates = new ArrayList<>(availableNetworks);
        Network active = manager.getActiveNetwork();
        if (active != null) candidates.add(0, active);
        for (Network network : candidates) {
            try {
                LinkProperties properties = manager.getLinkProperties(network);
                int mtu = properties == null ? 0 : properties.getMtu();
                if (mtu >= MIN_MTU) return mtu;
            } catch (RuntimeException ignored) { }
        }
        return 0;
    }

    /**
     * @return total bytes carried in both directions, proving a real transfer rather than only a
     *         successful connect. The request body is padded past {@code candidate} bytes and the
     *         entire response is drained, so a path that cannot move full-size segments times out
     *         here instead of after the tunnel is published.
     */
    private long probeMtuCandidate(String socksAddress, int candidate, int timeoutMs) throws Exception {
        try (Socket tunnel = openSocksTunnel(socksAddress, "example.com", 443, timeoutMs)) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket ssl = verifiedTlsSocket(factory, tunnel, "example.com", 443, timeoutMs)) {
                StringBuilder padding = new StringBuilder(Math.max(64, candidate));
                while (padding.length() < candidate) padding.append('a');
                byte[] payload = ("GET / HTTP/1.1\r\nHost: example.com\r\nX-Aethon-MTU-Probe: " + padding
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                OutputStream output = ssl.getOutputStream();
                output.write(payload);
                output.flush();
                InputStream input = ssl.getInputStream();
                byte[] buffer = new byte[4096];
                long inbound = 0;
                int read;
                boolean sawStatus = false;
                while ((read = input.read(buffer)) > 0) {
                    if (!sawStatus) {
                        String head = new String(buffer, 0, Math.min(read, 16), StandardCharsets.US_ASCII);
                        if (!head.startsWith("HTTP/")) throw new IllegalStateException("MTU probe returned no HTTP response");
                        sawStatus = true;
                    }
                    inbound += read;
                }
                if (!sawStatus) throw new IllegalStateException("MTU probe returned no HTTP response");
                if (inbound < 512) throw new IllegalStateException("MTU probe response was truncated at " + inbound + "B");
                return payload.length + inbound;
            }
        }
    }

    private String automaticMtuCacheKey(String protocol, int linkMtu) {
        Network network = availableNetworks.isEmpty() ? null : availableNetworks.iterator().next();
        String identity = network == null ? "default" : network.toString();
        return identity + ":" + protocol + ":link" + linkMtu;
    }

    private void scheduleGoolExitLookup(Intent request, long session, long pipelineStarted) {
        long lookup = locationLookupSequence.incrementAndGet();
        worker.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                if (!isLocationLookupCurrent(lookup, session)) return;
                GoolExit exit = lookupGoolExit(request, session);
                if (exit == null || !isLocationLookupCurrent(lookup, session)) return;
                currentEndpoint = exit.location;
                sendLog("Performance gool_location_detected=" + (SystemClock.elapsedRealtime() - pipelineStarted) + "ms");
                sendLog("VPN location detected: " + exit.location + " exit_ip=" + exit.address + " country=" + exit.countryCode);
                sendStatus(currentState, currentMessage);
            } catch (Throwable error) {
                if (!isLocationLookupCurrent(lookup, session)) return;
                currentEndpoint = getString(R.string.connection_location_unavailable);
                sendLog("VPN location state=unavailable: " + safeMessage(error));
                sendStatus(currentState, currentMessage);
            } finally {
                sendLog("Performance location_lookup=" + (SystemClock.elapsedRealtime() - started) + "ms protocol=gool");
            }
        });
    }

    private boolean isCurrentSession(Intent request, long session) {
        return !stopping && active && generation.get() == session && activeRequest == request;
    }

    private boolean establishVpn(Intent request, long session) throws Exception {
        // IPv6 is captured unconditionally. The TUN and its route table are built exactly once per
        // session and are never rebuilt on a network change - recovery only restarts the Aether core
        // - so deciding capture from the network that happens to be up at establish() time left a
        // real leak: a session started on IPv4-only cellular carried no ::/0 route, and moving to an
        // IPv6-capable Wi-Fi then sent every AAAA flow straight out of the underlying interface with
        // the device's real IPv6 address. Capturing always is the only route-table state that cannot
        // leak. The flag below is diagnostic only: it records what the underlying network offers and
        // does not gate anything, because the exit family is the core's decision per CONNECT.
        boolean upstreamIpv6 = ipv6ModeRequested(value(request, "ipMode", "v4")) && upstreamHasGlobalIpv6();
        logIpv6Decision(true, upstreamIpv6);
        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(effectiveMtu(request))
                .setBlocking(false)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126);

        String routing = value(request, "routing", "bypass-local");
        if ("bypass-local".equals(routing)) addPublicRoutes(builder);
        else {
            builder.addRoute("0.0.0.0", 0);
            builder.addRoute("::", 0);
        }

        if (request.getBooleanExtra("dnsLeak", true)) {
            // Both resolvers are public addresses covered by the tunnel routes above, so every
            // lookup - including the hostname behind Android Private DNS - is carried inside the
            // tunnel and no query reaches the carrier resolver.
            String dns = value(request, "dns", "").trim();
            for (String resolver : (dns.isEmpty() ? "1.1.1.1,1.0.0.1" : dns).split(",")) {
                String address = resolver.trim();
                builder.addDnsServer(address);
                builder.addRoute(address, address.contains(":") ? 128 : 32);
            }
        } else {
            // The VPN declares no resolver, so Android falls back to the underlying network's DNS.
            // Carrier resolvers live on RFC1918 addresses that the tunnel cannot reach, so those
            // queries leave the device in the clear. This is the explicit meaning of the setting,
            // but it must be visible in the log rather than silent.
            sendLog("Private DNS routing is disabled; DNS queries will use the underlying network "
                    + "and are not protected by the tunnel");
        }
        applySplitApps(builder, request);
        File config = writeTunConfig(request);
        // Neither Builder.establish() nor the HEV JNI entry point may run while runtimeLock is
        // held. Both are native calls of unbounded duration, and stopConnection()/stopRuntime()
        // need that same lock to tear a session down. Holding it across them let a wedged
        // establish() block Disconnect and every later Connect on the single lifecycle thread,
        // which is the "stuck in Connecting until airplane mode" failure mode. The lock now only
        // guards publishing and adopting the descriptor.
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("Android could not create the VPN interface");
        boolean adopted = false;
        try {
            synchronized (runtimeLock) {
                if (!isCurrentSession(request, session)) return false;
                vpnInterface = descriptor;
                adopted = true;
            }
            // A bridge stop that overran its bound leaves the previous tunnel worker alive, and
            // upstream TProxyStartService is a no-op while one exists. Starting on top of it would
            // publish a tunnel that silently carries nothing, so fail the attempt explicitly.
            if (!awaitBridgeHandover()) {
                throw new IllegalStateException("The previous tunnel bridge has not shut down yet");
            }
            try {
                File nativeDirectory = new File(getApplicationInfo().nativeLibraryDir);
                String abi = CoreIntegrity.verify(new File(nativeDirectory, "libaether.so"));
                NativeIntegrity.verify(new File(nativeDirectory, NativeIntegrity.HEV), abi);
                TProxyService.TProxyStartService(config.getAbsolutePath(), descriptor.getFd());
            } catch (UnsatisfiedLinkError error) {
                throw new IllegalStateException("The HEV Android JNI bridge could not be loaded", error);
            }
            boolean orphaned;
            synchronized (runtimeLock) {
                // A teardown replaced or cleared the descriptor while the bridge was starting; the
                // bridge it just attached to is now orphaned and has to be stopped here.
                orphaned = vpnInterface != descriptor;
                if (!orphaned) bridgeStarted = true;
            }
            // Deliberately outside runtimeLock, for the reason stopBridge() documents.
            if (orphaned) { stopBridge(); return false; }
            sendLog("HEV Android TUN bridge started");
            return isCurrentSession(request, session);
        } finally {
            if (!adopted) {
                try { descriptor.close(); }
                catch (Exception ignored) { }
            }
        }
    }

    /**
     * Kills Aether cores left behind by a previous session or a process death. Android mounts
     * /proc with hidepid. Verify both the app UID and exact executable before terminating a
     * process; a matching command-line substring does not establish ownership. A leftover
     * listener is what makes a later connect appear ready while carrying no traffic, and is the
     * documented cause of "multiple Connect attempts" and "airplane mode toggle required".
     */
    private void reapOrphanedCores() {
        Process current = aetherProcess;
        if (current != null && current.isAlive()) return;
        int self = android.os.Process.myPid();
        java.util.Set<String> expectedExecutables = new java.util.HashSet<>();
        try {
            for (String library : new String[]{"libaether.so", NativeIntegrity.PSIPHON, NativeIntegrity.LYREBIRD})
                expectedExecutables.add(new File(getApplicationInfo().nativeLibraryDir, library).getCanonicalPath());
        }
        catch (IOException unavailable) { return; }
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int killed = 0;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException notAProcess) { continue; }
            if (pid == self) continue;
            try {
                String executable = new File(entry, "exe").getCanonicalPath();
                if (!CoreProcessGuard.isOwnedExecutable(executable, expectedExecutables,
                        readProcFile(new File(entry, "status")), android.os.Process.myUid())) continue;
                android.os.Process.killProcess(pid);
                killed++;
                sendLog("Cleared a leftover owned Core/helper process pid=" + pid);
            } catch (IOException | RuntimeException error) {
                sendLog("Could not clear leftover Aether core pid=" + pid + ": " + safeMessage(error));
            }
        }
        if (killed > 0) sendLog(getString(R.string.service_stale_core_cleared));
    }

    private static String readProcFile(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[512];
            int read = input.read(buffer);
            if (read <= 0) return "";
            return new String(buffer, 0, read, StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (Exception unreadable) {
            return "";
        }
    }

    /**
     * Waits until nothing is listening on the SOCKS port. Without this a stale listener makes
     * {@link #waitForSocks} succeed instantly against the wrong process, publishing a tunnel that
     * carries no traffic.
     *
     * @return true when the port is free
     */
    private boolean awaitSocksPortReleased(String socksAddress) {
        return awaitListenerPortReleased("SOCKS5", socksAddress);
    }

    private boolean awaitListenerPortReleased(String listener, String address) {
        HostPort target;
        try { target = HostPort.parse(address); }
        catch (IllegalArgumentException invalid) { return true; }
        long deadline = SystemClock.elapsedRealtime() + SOCKS_PORT_RELEASE_TIMEOUT_MS;
        boolean reported = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!portAccepts(target)) return true;
            if (!reported) {
                sendLog(listener + " port " + target.port + " is still held; waiting for it to be released");
                reported = true;
            }
            try { Thread.sleep(100L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        sendLog(listener + " port " + target.port + " did not free up within "
                + SOCKS_PORT_RELEASE_TIMEOUT_MS + "ms; the new core may fail to bind");
        return false;
    }

    private static boolean portAccepts(HostPort target) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(target.host, target.port), 200);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    /**
     * Starts the public 1818/1819 relays once the privacy listeners have proven live. Only the
     * Chain topologies need them: they are the whole published proxy contract in those modes, so a
     * session that could not start them is not allowed to publish Connected - a half-served
     * contract (SOCKS answered by the relay, HTTP by nothing) is exactly the silent partial
     * feature this build must not reintroduce.
     */
    private void startPublicRelays(Intent request) {
        if (activeRequest != request) return;
        Map<String, Object> settings = ProxyMode.settings(value(request, "connectionMode", "vpn"),
                AndroidCoreSettings.fromIntent(request));
        String upstreamSocks;
        String upstreamHttp;
        if (PsiphonChainRouting.chainActive(settings)) {
            upstreamSocks = PrivacyRuntimeConfig.PSIPHON_SOCKS;
            upstreamHttp = PrivacyRuntimeConfig.PSIPHON_HTTP;
        } else if (TorChainRouting.chainActive(settings)) {
            // dev.017 CHANGE 7: with Tor as the full-device exit, the public contract is served
            // from the Tor SOCKS listener on 1819 and the Tor HTTP listener on 1818, both
            // auto-provisioned exactly like the Psiphon chain provisions its listeners.
            upstreamSocks = PrivacyRuntimeConfig.TOR_SOCKS;
            upstreamHttp = PrivacyRuntimeConfig.TOR_HTTP;
        } else return;
        stopPublicRelays();
        try {
            publicRelays = PublicPortRelays.start(ProxyMode.SOCKS_ADDRESS, ProxyMode.HTTP_ADDRESS,
                    upstreamSocks, upstreamHttp);
            sendLog("Public proxy contract is served by the privacy chain: "
                    + ProxyMode.SOCKS_ADDRESS + " -> " + upstreamSocks + ", "
                    + ProxyMode.HTTP_ADDRESS + " -> " + upstreamHttp);
        } catch (Exception unavailable) {
            publicRelays = null;
            throw new IllegalStateException("The public proxy ports could not be forwarded to the privacy chain", unavailable);
        }
    }

    private void stopPublicRelays() {
        PublicPortRelays relays = publicRelays;
        publicRelays = null;
        if (relays != null) relays.close();
    }

    private void startAether(Intent request, long startingSession) throws Exception {
        if (stopping || generation.get() != startingSession) throw new InterruptedException("Core startup was cancelled");
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libaether.so");
        if (!executable.isFile()) throw new IllegalStateException("Aether core is missing for this device architecture");

        long integrityStarted = SystemClock.elapsedRealtime();
        String verifiedAbi = CoreIntegrity.verify(executable);
        sendLog("Performance core_integrity=" + (SystemClock.elapsedRealtime() - integrityStarted) + "ms");
        String mode = value(request, "connectionMode", "vpn");
        Map<String, Object> coreSettings = ProxyMode.settings(mode, AndroidCoreSettings.fromIntent(request));
        // Full-device chaining (dev.016/dev.017): while a Psiphon or Tor Chain combination is
        // active, the Core's base SOCKS listener moves to the internal port and becomes only the
        // underlay that carries the privacy helper's upstream. The effective tunnel target - for
        // HEV, readiness, the traffic gate, health checks and location lookups alike - is the
        // privacy final proxy, and the public 1818/1819 contract is served by relays in front of
        // the privacy listeners. With both chains off, both addresses are exactly what dev.015
        // used.
        boolean torChain = TorChainRouting.chainActive(coreSettings);
        String coreBindSocks = torChain
                ? TorChainRouting.coreSocksBind(coreSettings, mode, value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT))
                : PsiphonChainRouting.coreSocksBind(coreSettings, mode, value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT));
        String effectiveSocks = torChain
                ? TorChainRouting.effectiveSocks(coreSettings, mode, value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT))
                : PsiphonChainRouting.effectiveSocks(coreSettings, mode, value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT));
        request.putExtra("socks", effectiveSocks);
        if (!effectiveSocks.equals(coreBindSocks)) {
            sendLog((torChain ? "Tor chain active: base SOCKS bound internally on "
                    : "Psiphon chain active: base SOCKS bound internally on ") + coreBindSocks
                    + "; device egress and public 1818/1819 are served through " + effectiveSocks);
        }
        // dev.017 CHANGE 5: Proxy mode never starts the internal full-device chains; the
        // saved preferences are not rewritten, so returning to Device VPN reactivates them.
        String modeInvalid = PrivacySettings.invalidForMode(coreSettings,
                value(request, "protocol", ConnectionDefaults.PROTOCOL), mode);
        if (modeInvalid != null)
            throw new IllegalArgumentException("Invalid privacy setting: " + modeInvalid);
        Map<String, String> coreEnvironment = CoreSettings.environment(coreSettings, value(request, "protocol", ConnectionDefaults.PROTOCOL), value(request, "transport", ConnectionDefaults.TRANSPORT));
        PrivacyRuntimeConfig.validateListeners(coreSettings, effectiveSocks,
                request.getBooleanExtra("lanEnabled", false) ? request.getIntExtra("lanPort", 18190) : null);

        // A previous core that outlived its session still owns the SOCKS port and its upstream
        // sockets. Clearing it here is what makes a repeated Connect deterministic.
        reapOrphanedCores();
        for (Map.Entry<String, String> listener : PrivacyRuntimeConfig.listenerAddresses(coreSettings, effectiveSocks).entrySet()) {
            if (!awaitListenerPortReleased(listener.getKey(), listener.getValue()))
                throw new IllegalStateException("The previous " + listener.getKey() + " listener has not shut down");
        }
        // In Chain mode the app itself binds the public 1818/1819 relays only after the privacy
        // listeners are ready, but the ports must be free now: a stale relay or an unrelated
        // process would otherwise turn a connected session into one whose public contract is
        // served by something else. This is the same preflight the manual mode has always run.
        if (PsiphonChainRouting.chainActive(coreSettings) || TorChainRouting.chainActive(coreSettings)) {
            try { ProxyMode.checkAvailable(ProxyMode.SOCKS_ADDRESS, ProxyMode.HTTP_ADDRESS); }
            catch (ProxyMode.PortUnavailableException occupied) {
                throw new ProxyPortException(getString(R.string.service_proxy_port_unavailable,
                        occupied.listener, occupied.address), occupied);
            }
        }
        if (ProxyMode.enabled(mode)) {
            try { ProxyMode.checkAvailable(ProxyMode.SOCKS_ADDRESS, ProxyMode.HTTP_ADDRESS); }
            catch (ProxyMode.PortUnavailableException occupied) {
                throw new ProxyPortException(getString(R.string.service_proxy_port_unavailable,
                        occupied.listener, occupied.address), occupied);
            }
        }

        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath());
        builder.directory(getFilesDir());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        CoreSettings.clearInheritedEnvironment(env);
        env.put("AETHER_PROTOCOL", value(request, "protocol", ConnectionDefaults.PROTOCOL));
        env.put("AETHER_SCAN", ConnectionDefaults.normalizedScanMode(value(request, "scan", ConnectionDefaults.SCAN)));
        env.put("AETHER_IP", value(request, "ipMode", "v4"));
        env.put("AETHER_NOIZE", value(request, "obfuscation", ConnectionDefaults.OBFUSCATION));
        // Keep core diagnostics enabled internally; there is no user-facing log-level control.
        env.put("AETHER_LOG_LEVEL", "info");
        env.put("AETHER_SOCKS", coreBindSocks);
        env.put("AETHER_CONFIG", new File(getFilesDir(), "aether.toml").getAbsolutePath());
        env.put("AETHER_QUICK_RECONNECT", request.getBooleanExtra("quickReconnect", true) ? "1" : "0");
        String protocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        String transport = value(request, "transport", ConnectionDefaults.TRANSPORT);
        // dev.034: Gool over MASQUE rides a MASQUE outer tunnel, so the carrier selection
        // (HTTP/3 vs HTTP/2) and the MASQUE MTU apply to it exactly as to masque/mim. On
        // v2.3.0 leaving AETHER_MASQUE_HTTP2 unset would hit the Core's interactive carrier
        // prompt with the service's closed stdin; setting it is also what makes the primary
        // screen's MASQUE Connection Method control the real owner of the gool carrier.
        if (CoreSettings.masque(protocol) || CoreSettings.goolOverMasque(protocol, coreSettings)) {
            env.put("AETHER_MASQUE_HTTP2", "h2".equals(transport) ? "1" : "0");
            // v2.0.0 subtracts inner encapsulation from this OUTER budget. MIM's TUN is
            // capped at 1280; a 1400-byte outer tunnel also carries an IPv6 QUIC inner hop.
            env.put("AETHER_MASQUE_MTU", Integer.toString("mim".equals(protocol) ? 1400 : effectiveMtu(request)));
        }
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        String peer = request.getStringExtra("peer");
        if (peer != null && !peer.trim().isEmpty()) {
            if (!CoreSettings.endpoint(peer.trim(), false)) throw new IllegalArgumentException("Invalid peer endpoint");
            env.put("AETHER_PEER", peer.trim());
        }

        env.putAll(coreEnvironment);
        // Organization identities must never reuse a personal or another team's identity.
        String team = CoreSettings.string(coreSettings, "team").toLowerCase(Locale.ROOT);
        if (!team.isEmpty()) env.put("AETHER_CONFIG", new File(getFilesDir(), "aether-team-" + team + ".toml").getAbsolutePath());
        env.putAll(PrivacyRuntimeAssets.prepare(getContentResolver(), new File(getApplicationInfo().nativeLibraryDir),
                getFilesDir(), verifiedAbi, coreSettings));

        masqueH3GatewayUnavailable = false;
        runtimeGoolTopology = null;
        runtimeGoolTopologyProof = null;

        Process process;
        ProxyMode.Listeners listeners = ProxyMode.enabled(mode)
                ? new ProxyMode.Listeners(CoreSettings.string(coreSettings, "psiphonMode")) : null;
        RuntimeTrafficPlan.Announcements announcements = new RuntimeTrafficPlan.Announcements(coreSettings, effectiveSocks);
        synchronized (runtimeLock) {
            if (stopping || generation.get() != startingSession) throw new InterruptedException("Core startup was cancelled");
            if (aetherProcess != null && aetherProcess.isAlive()) {
                throw new ProxyStartupException(getString(R.string.service_core_still_stopping));
            }
            process = builder.start();
            aetherProcess = process;
            proxyListeners = listeners;
            privacyAnnouncements = announcements;
            readinessTrace.clear();
        }
        sendLog("Performance core_process_started=" + elapsedSinceRequest(request) + "ms");
        sendLog("Verified Aether Core " + BuildConfig.AETHER_VERSION + " SHA-256 for " + verifiedAbi);
        process.getOutputStream().close();
        Thread logs = new Thread(() -> readAetherLogs(process, protocol, transport, listeners, announcements), "aether-log-reader");
        logs.setDaemon(true);
        logs.start();
    }

    private void readAetherLogs(Process process, String protocol, String transport, ProxyMode.Listeners listeners,
                                RuntimeTrafficPlan.Announcements announcements) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Aether] " + line);
                String lower = line.toLowerCase(Locale.US);
                if (process == aetherProcess && listeners != null) listeners.onCoreLog(lower);
                if (process == aetherProcess) announcements.onCoreLog(lower);
                if (process == aetherProcess && CoreSettings.masque(protocol) && "h3".equals(transport)
                        && lower.contains("no usable masque gateway found")) {
                    masqueH3GatewayUnavailable = true;
                }
                if (process == aetherProcess && "gool".equals(protocol)) {
                    // The Core's own lines prove which of the two v2.3.0 Gool topologies is
                    // actually running; the requested mode is only a request until one of
                    // these arrives (PROMPT §4.5/§4.6).
                    if (lower.contains("gool ready: masque ") && lower.contains(" carries wireguard ")) {
                        runtimeGoolTopology = "masque";
                        runtimeGoolTopologyProof = line.trim();
                    } else if (lower.contains("establishing inner warp tunnel (warp-in-warp)")) {
                        runtimeGoolTopology = "classic";
                        runtimeGoolTopologyProof = line.trim();
                    }
                }
                if (process == aetherProcess && !stopping && !smartBenchmarking && isConnectingState(currentState)) {
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
            long recoveryStarted = SystemClock.elapsedRealtime();
            updateState("reconnecting", getString(R.string.service_reconnecting));
            updateNotification(getString(R.string.service_reconnecting));
            Thread.sleep(Math.min(20_000L, 1_500L << (attempts - 1)));
            if (!startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS, session)) {
                sendLog(aetherExitMessage("Aether reconnect attempt did not become ready"));
                Process retry = aetherProcess;
                if (retry != null && retry.isAlive()) retry.destroy();
                continue;
            }
            // A restarted core that opens its SOCKS listener has not proven it can carry traffic.
            // Publishing "connected" on the listener alone is exactly the false-Connected state
            // PROMPT phase 3 forbids, so the same real HTTPS gate runs before the state flips back.
            try {
                if (!validateTrafficReady(request, session, SystemClock.elapsedRealtime(),
                        RECOVERY_TRAFFIC_READY_ATTEMPTS)) return;
            } catch (Exception unproven) {
                sendLog("Recovered core could not carry real traffic; retrying: " + safeMessage(unproven));
                stopAetherOnly();
                continue;
            }
            if (stopping || generation.get() != session) return;
            recoveryRestartPending.set(false);
            // A recovered core rebuilt the Psiphon helper from scratch, so the public relays that
            // were stopped with the dead core have to be re-bound in front of the new listeners
            // before Connected is published again.
            startPublicRelays(request);
            updateState("connected", getString(R.string.service_restored));
            updateNotification(getString(R.string.service_restored));
            sendLog("Performance reconnect_published=" + (SystemClock.elapsedRealtime() - recoveryStarted) + "ms attempt=" + attempts);
            if (usesWarp(request) && "gool".equals(value(request, "protocol", ConnectionDefaults.PROTOCOL))) {
                scheduleGoolExitLookup(request, session, SystemClock.elapsedRealtime());
            } else scheduleLocationLookup(request, session);
        }
    }

    private void waitForUnderlyingNetwork(long session) throws InterruptedException {
        while (networkUnavailable && !stopping && generation.get() == session) {
            updateState("reconnecting", getString(R.string.service_network_lost));
            updateNotification(getString(R.string.service_network_lost));
            synchronized (networkLock) { networkLock.wait(30_000L); }
        }
    }

    /**
     * Readiness means the listener answers a real SOCKS5 method-selection handshake, not merely
     * that the TCP port accepts. A half-open or wedged listener passes a bare connect test and then
     * publishes a tunnel that carries nothing, so the greeting is the minimum honest signal.
     */
    private boolean waitForSocks(String address, long timeoutMs) {
        HostPort target = HostPort.parse(address);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping && System.currentTimeMillis() < deadline) {
            Process process = aetherProcess;
            if (process != null && !process.isAlive()) return false;
            if (masqueH3GatewayUnavailable) return false;
            if (socksHandshakeSucceeds(target)) return true;
            try { Thread.sleep(100); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private static boolean socksHandshakeSucceeds(HostPort target) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host, target.port), 250);
            socket.setSoTimeout(400);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            byte[] greeting = readExact(socket.getInputStream(), 2);
            return greeting[0] == 5 && greeting[1] == 0;
        } catch (Exception notReady) {
            return false;
        }
    }

    private boolean waitForRequiredListeners(Intent request, long timeoutMs) throws ProxyStartupException {
        // In Chain mode the published readiness line is the Psiphon final proxy itself: the base
        // listener lives on the internal port and the public 1818/1819 ports are the app's
        // relays, which start only after the whole data plane is proven. The full-tunnel
        // announcement wait that follows in the traffic gate is what guarantees the Psiphon path
        // actually carries traffic before Connected is published.
        Map<String, Object> settings = ProxyMode.settings(value(request, "connectionMode", "vpn"),
                AndroidCoreSettings.fromIntent(request));
        if (PsiphonChainRouting.chainActive(settings) || TorChainRouting.chainActive(settings))
            return waitForSocks(value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT), timeoutMs);
        if (!ProxyMode.enabled(value(request, "connectionMode", "vpn"))) {
            return waitForSocks(value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT), timeoutMs);
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            ProxyMode.Listeners listeners = proxyListeners;
            if (listeners != null && listeners.failedListener() != null) {
                String listener = listeners.failedListener();
                throw new ProxyPortException(getString(R.string.service_proxy_port_unavailable, listener,
                        "HTTP".equals(listener) ? ProxyMode.HTTP_ADDRESS : ProxyMode.SOCKS_ADDRESS));
            }
            Process process = aetherProcess;
            if (process == null || !process.isAlive() || masqueH3GatewayUnavailable) return false;
            if (proxyListenersRespond(process)) return true;
            try { Thread.sleep(100); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private boolean proxyListenersRespond(Process process) {
        ProxyMode.Listeners listeners = proxyListeners;
        return process != null && process == aetherProcess && process.isAlive()
                && listeners != null && listeners.announced()
                && socksHandshakeSucceeds(HostPort.parse(ProxyMode.SOCKS_ADDRESS))
                && ProxyMode.httpResponds(ProxyMode.HTTP_ADDRESS)
                && process == aetherProcess && process.isAlive() && !stopping;
    }

    private boolean listenerStartupFailed(Intent request) throws ProxyStartupException {
        if (!stopping && ProxyMode.enabled(value(request, "connectionMode", "vpn"))) {
            throw new ProxyStartupException(getString(R.string.service_proxy_not_ready,
                    ProxyMode.SOCKS_ADDRESS, ProxyMode.HTTP_ADDRESS));
        }
        return false;
    }

    private boolean startAetherWithMasqueFallback(Intent request, long timeoutMs, long session) throws Exception {
        long started = SystemClock.elapsedRealtime();
        startAether(request, session);
        boolean masque = usesWarp(request) && CoreSettings.masque(value(request, "protocol", ConnectionDefaults.PROTOCOL));
        if (masque)
            timeoutMs = Math.max(timeoutMs, request.getIntExtra("startupSecs", 30) * 1000L * ("mim".equals(value(request, "protocol", "")) ? 2 : 1));
        // A full-device Tor chain's SOCKS listener only opens after Tor bootstraps its consensus
        // through the WARP underlay, which can take over a minute on first run.
        if (TorChainRouting.chainActive(ProxyMode.settings(value(request, "connectionMode", "vpn"),
                AndroidCoreSettings.fromIntent(request))))
            timeoutMs = Math.max(timeoutMs, SOCKS_TIMEOUT_MS + TOR_CHAIN_STARTUP_ALLOWANCE_MS);
        boolean masqueH3 = masque && "h3".equals(value(request, "transport", ConnectionDefaults.TRANSPORT));
        long primaryTimeout = masqueH3 ? Math.min(timeoutMs, MASQUE_H3_PRIMARY_TIMEOUT_MS) : timeoutMs;
        if (waitForRequiredListeners(request, primaryTimeout)) return true;
        if (stopping || !masqueH3) {
            return listenerStartupFailed(request);
        }
        sendLog(masqueH3GatewayUnavailable
                ? "MASQUE HTTP/3 gateway scan failed; retrying with HTTP/2 transport"
                : "MASQUE HTTP/3 did not establish; retrying with HTTP/2 transport");
        stopAetherOnly();
        request.putExtra("transport", "h2");
        updateState("scanning", getString(R.string.service_scanning));
        startAether(request, session);
        long remaining = Math.max(5_000L, timeoutMs - (SystemClock.elapsedRealtime() - started));
        return waitForRequiredListeners(request, remaining) || listenerStartupFailed(request);
    }

    private String chooseSmartProtocol(Intent request, long session) throws Exception {
        String remembered = stateStore.getString("smartProtocol", "");
        String[] protocols = {"wg", "masque", "gool"};
        if ("masque".equals(remembered)) protocols = new String[]{"masque", "wg", "gool"};
        else if ("gool".equals(remembered)) protocols = new String[]{"gool", "wg", "masque"};
        SmartResult best = null;
        String bestTransport = value(request, "transport", ConnectionDefaults.TRANSPORT);
        smartBenchmarking = true;
        try {
            for (int index = 0; index < protocols.length; index++) {
                if (stopping || generation.get() != session) throw new InterruptedException("Smart Connect was cancelled");
                String protocol = protocols[index];
                updateState("smart-testing", getString(R.string.service_testing_protocol, protocolLabel(protocol), index + 1, protocols.length));
                Intent trial = new Intent(request).putExtra("protocol", protocol).putExtra("quickReconnect", false);
                long candidateStarted = SystemClock.elapsedRealtime();
                SmartResult result = benchmarkProtocol(trial, protocol, session);
                sendLog(result.summary());
                sendLog("Performance smart_candidate " + protocol + " total=" + (SystemClock.elapsedRealtime() - candidateStarted) + "ms");
                if (best == null || result.score > best.score) {
                    best = result;
                    bestTransport = value(trial, "transport", ConnectionDefaults.TRANSPORT);
                }
                stopAetherOnly();
                // A strong, data-plane-validated candidate cannot realistically be beaten by a
                // slower remaining protocol. This keeps Smart Connect within normal connect UX.
                if (index >= 1 && best.connected && best.score >= SMART_EARLY_ACCEPT_SCORE) break;
            }
        } finally {
            smartBenchmarking = false;
            stopAetherOnly();
        }
        if (best == null || !best.connected) throw new IllegalStateException("Smart Connect could not establish any available protocol");
        if ("masque".equals(best.protocol)) request.putExtra("transport", bestTransport);
        return best.protocol;
    }

    private SmartResult benchmarkProtocol(Intent request, String protocol, long session) throws ProxyPortException {
        long started = System.nanoTime();
        try {
            String socks = value(request, "socks", "127.0.0.1:1819");
            boolean connected = startAetherWithMasqueFallback(request, SMART_PROTOCOL_TIMEOUT_MS, session);
            long handshakeMs = elapsedMillis(started);
            if (!connected) return SmartResult.failed(protocol, handshakeMs);
            long latencyMs = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
            long dnsMs = socksConnectMillis(socks, "cloudflare.com", 443, 5_000);
            int attempts = 2;
            int stable = 0;
            long latencyTotal = 0;
            for (int i = 0; i < attempts; i++) {
                if (stopping) break;
                try {
                    long probe = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
                    latencyTotal += probe;
                    stable++;
                } catch (Exception ignored) { }
            }
            if (stable > 0) latencyMs = Math.min(latencyMs, latencyTotal / stable);
            return SmartResult.success(protocol, handshakeMs, latencyMs, dnsMs, stable, attempts);
        } catch (ProxyPortException occupied) {
            throw occupied;
        } catch (Throwable error) {
            return SmartResult.failed(protocol, elapsedMillis(started));
        }
    }

    private Socket openSocksTunnel(String socksAddress, String host, int port, int timeoutMs) throws Exception {
        return openSocksTunnel(socksAddress, host, port, timeoutMs, null);
    }

    private Socket openSocksTunnel(String socksAddress, String host, int port, int timeoutMs, ProbeCancellation cancellation) throws Exception {
        HostPort proxy = HostPort.parse(socksAddress);
        Socket socket = new Socket();
        try {
            if (cancellation != null) cancellation.track(socket);
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
            long started = SystemClock.elapsedRealtime();
            String location = "";
            try {
                if (!isLocationLookupCurrent(lookup, session)) return;
                String socksAddress = value(request, "socks", "127.0.0.1:1819");
                String address = "";
                String traceCountry = "";
                try {
                    String trace = locationTrace(socksAddress, session);
                    address = traceValue(trace, "ip");
                    String rawTraceCountry = traceValue(trace, "loc");
                    traceCountry = normalizedCountryCode(rawTraceCountry, "");
                    if (!address.isEmpty() && !traceCountry.isEmpty()) {
                        location = countryFlag(traceCountry) + " " + traceCountry;
                        sendLog("VPN location detected from Cloudflare trace: " + location);
                    } else if (!address.isEmpty() && isNonCountryMarker(rawTraceCountry)) {
                        // dev.020 CHANGE 6: the trace answered with Cloudflare's Tor marker
                        // (T1/T2/XX) instead of a country. T1 is NOT an ISO country code and
                        // must never be rendered as a flag; the real final Tor exit country is
                        // resolved below through the same trustworthy geo-IP providers the
                        // lookup already uses for this exit IP (no new dependency).
                        sendLog("Trace reported the Tor marker " + rawTraceCountry
                                + " instead of a country; resolving the real Tor exit country");
                    }
                } catch (Throwable traceError) {
                    sendLog("Cloudflare VPN location trace failed; trying geo providers: " + safeMessage(traceError));
                }
                if (address.isEmpty()) {
                    JSONObject ipJson = new JSONObject(socksHttpGet(socksAddress, "api.ipify.org", "/?format=json"));
                    address = ipJson.optString("ip", "").trim();
                }
                if (address.isEmpty()) throw new IllegalStateException("VPN public IP was empty");
                // A reconnect that lands on the same exit IP cannot have changed location, so the
                // cached answer is returned immediately instead of re-querying the providers.
                String cachedLocation = cachedLocationFor(address);
                if (!cachedLocation.isEmpty()) {
                    location = cachedLocation;
                    sendLog("Reusing cached VPN location for unchanged exit IP");
                } else if (location.isEmpty() && System.currentTimeMillis() >= stateStore.getLong("locationPrimaryBackoffUntil", 0L)) {
                    try {
                        JSONObject provider = new JSONObject(socksHttpGet(socksAddress, "ipapi.co", "/" + address + "/json/"));
                        if (providerMatchesAddress(provider, address)) location = locationFromJson(provider);
                        else sendLog("Ignoring location provider result for a different exit IP");
                    } catch (Throwable primaryError) {
                        if (providerShouldBackOff(safeMessage(primaryError))) {
                            stateStore.edit().putLong("locationPrimaryBackoffUntil", System.currentTimeMillis() + LOCATION_PRIMARY_BACKOFF_MS).apply();
                            sendLog("Primary VPN location provider rate-limited; backing off for one hour");
                        } else {
                            sendLog("Primary VPN location provider failed; trying fallback: " + safeMessage(primaryError));
                        }
                    }
                } else if (location.isEmpty()) {
                    sendLog("Skipping rate-limited primary VPN location provider during backoff");
                }
                if (location.isEmpty() && isLocationLookupCurrent(lookup, session)) {
                    try {
                        JSONObject provider = new JSONObject(socksHttpGet(socksAddress, "ipwho.is", "/" + address));
                        if (providerMatchesAddress(provider, address)) location = locationFromJson(provider);
                        else sendLog("Ignoring fallback location result for a different exit IP");
                        if (location.isEmpty()) sendLog("Fallback VPN location provider returned no city/country");
                    } catch (Throwable fallbackError) {
                        sendLog("Fallback VPN location provider failed: " + safeMessage(fallbackError));
                    }
                }
                // dev.020 CHANGE 6: even a cached location is only trusted when its stored
                // country is a real ISO country — a stale T1-style value can never re-enter
                // the display path.
                if (!location.isEmpty() && !countryCodesValid(location)) {
                    sendLog("Discarding non-country cached location value");
                    location = "";
                }
                if (!location.isEmpty() && isLocationLookupCurrent(lookup, session)) {
                    stateStore.edit()
                            .putInt(LOCATION_CACHE_VERSION_KEY, LOCATION_CACHE_VERSION)
                            .putString(LOCATION_CACHE_VALUE_KEY, location)
                            .putString(LOCATION_CACHE_IP_KEY, address)
                            .putString(LOCATION_CACHE_COUNTRY_KEY, traceCountry)
                            .putLong(LOCATION_CACHE_TIME_KEY, System.currentTimeMillis())
                            .apply();
                }
            } catch (Throwable error) {
                if (isLocationLookupCurrent(lookup, session)) {
                    sendLog("VPN location detection failed: " + safeMessage(error));
                }
            }
            if (isLocationLookupCurrent(lookup, session)) {
                currentEndpoint = location.isEmpty()
                        ? getString(R.string.connection_location_unavailable)
                        : location;
                sendLog(location.isEmpty() ? "VPN location state=unavailable" : "VPN location detected: " + location);
                sendLog("Performance location_lookup=" + (SystemClock.elapsedRealtime() - started) + "ms");
                sendStatus(currentState, currentMessage);
            }
        });
    }

    /**
     * dev.020 CHANGE 6: whether a rendered location line (e.g. "🇩🇪 DE" or "🇩🇪 Berlin") is built
     * from a real ISO country. The renderer always prefixes a two-regional-indicator flag when
     * the code was valid, so the line must start with exactly two flag characters; the remainder
     * (an ISO code, or a city name followed by an optional code) must consist of Unicode
     * letters only — the Tor marker "T1" and any digit-bearing fragment fail, so such a value
     * degrades to the truthful "Location unavailable" state instead of a fabricated country.
     */
    static boolean countryCodesValid(String renderedLocation) {
        if (renderedLocation == null) return false;
        String line = renderedLocation.trim();
        if (line.isEmpty()) return false;
        int[] codePoints = line.codePoints().toArray();
        if (codePoints.length < 3) return false;
        for (int i = 0; i < 2; i++) {
            if (codePoints[i] < 0x1F1E6 || codePoints[i] > 0x1F1FF) return false;
        }
        String remainder = new String(codePoints, 2, codePoints.length - 2).trim();
        if (remainder.isEmpty()) return false;
        for (int codePoint : remainder.codePoints().toArray()) {
            // Letters and word-joining spaces only: the Tor marker "T1" (and any digit-bearing
            // or punctuation fragment) fails here.
            if (!Character.isLetter(codePoint) && codePoint != ' ') return false;
        }
        return true;
    }

    private boolean isLocationLookupCurrent(long lookup, long session) {
        return lookup == locationLookupSequence.get() && !stopping
                && generation.get() == session && "connected".equals(currentState);
    }

    private String cachedLocationFor(String exitIp) {
        if (exitIp.isEmpty()) return "";
        if (stateStore.getInt(LOCATION_CACHE_VERSION_KEY, 0) != LOCATION_CACHE_VERSION) return "";
        if (!exitIp.equals(stateStore.getString(LOCATION_CACHE_IP_KEY, ""))) return "";
        String cached = stateStore.getString(LOCATION_CACHE_VALUE_KEY, "");
        return cached == null ? "" : cached;
    }

    private String locationTrace(String socksAddress, long session) throws Exception {
        Process process = aetherProcess;
        String trace = readinessTrace.take(process, session, SystemClock.elapsedRealtime());
        if (trace != null && process != null && process.isAlive()) {
            sendLog("Performance location_trace_reused_from_readiness=1");
            return trace;
        }
        return socksHttpGet(socksAddress, "www.cloudflare.com", "/cdn-cgi/trace");
    }

    private GoolExit lookupGoolExit(Intent request, long session) throws Exception {
        String socksAddress = value(request, "socks", "127.0.0.1:1819");
        JSONObject geo = null;
        String address = "";
        try {
            // Cloudflare's trace endpoint returns the exit IP and ISO country in one small
            // response and is substantially less prone to rate limiting than public geo APIs.
            String trace = locationTrace(socksAddress, session);
            address = traceValue(trace, "ip");
            String country = normalizedCountryCode(traceValue(trace, "loc"), "");
            if (!address.isEmpty() && !country.isEmpty()) {
                String cachedLocation = cachedLocationFor(address);
                String cachedCountry = address.equals(stateStore.getString(LOCATION_CACHE_IP_KEY, ""))
                        ? stateStore.getString(LOCATION_CACHE_COUNTRY_KEY, "") : "";
                if (!cachedCountry.isEmpty()) country = cachedCountry;
                String location = cachedLocation.isEmpty() ? countryFlag(country) + " " + country : cachedLocation;
                stateStore.edit().putInt(LOCATION_CACHE_VERSION_KEY, LOCATION_CACHE_VERSION)
                        .putString(LOCATION_CACHE_VALUE_KEY, location)
                        .putString(LOCATION_CACHE_IP_KEY, address)
                        .putString(LOCATION_CACHE_COUNTRY_KEY, country)
                        .putLong(LOCATION_CACHE_TIME_KEY, System.currentTimeMillis()).apply();
                return new GoolExit(address, country, location);
            }
        } catch (Throwable traceError) {
            sendLog("Cloudflare exit trace failed; trying geo providers: " + safeMessage(traceError));
        }
        try {
            geo = new JSONObject(socksHttpGet(socksAddress, "ipwho.is", "/"));
            address = geo.optString("ip", "").trim();
            if (address.isEmpty() || countryCodeFromJson(geo).isEmpty() || !providerMatchesAddress(geo, address)) geo = null;
        } catch (Throwable fastLookupError) {
            sendLog("Fast gool country lookup failed; trying fallback: " + safeMessage(fastLookupError));
        }
        if (geo == null) {
            try {
                geo = new JSONObject(socksHttpGet(socksAddress, "api.country.is", "/"));
                address = geo.optString("ip", "").trim();
                if (address.isEmpty() || countryCodeFromJson(geo).isEmpty() || !providerMatchesAddress(geo, address)) geo = null;
            } catch (Throwable countryError) {
                sendLog("Country service fallback failed; trying IP geo providers: " + safeMessage(countryError));
            }
        }
        if (geo == null) {
            JSONObject ipJson = new JSONObject(socksHttpGet(socksAddress, "api.ipify.org", "/?format=json"));
            address = ipJson.optString("ip", "").trim();
            if (address.isEmpty()) throw new IllegalStateException("VPN public IP was empty");
        }
        String cachedCountry = address.equals(stateStore.getString(LOCATION_CACHE_IP_KEY, ""))
                ? stateStore.getString(LOCATION_CACHE_COUNTRY_KEY, "") : "";
        String cachedLocation = cachedLocationFor(address);
        if (!cachedCountry.isEmpty()) return new GoolExit(address, cachedCountry, cachedLocation);

        if (geo == null && System.currentTimeMillis() >= stateStore.getLong("locationPrimaryBackoffUntil", 0L)) {
            try {
                geo = new JSONObject(socksHttpGet(socksAddress, "ipapi.co", "/" + address + "/json/"));
            } catch (Throwable primaryError) {
                if (providerShouldBackOff(safeMessage(primaryError))) {
                    stateStore.edit().putLong("locationPrimaryBackoffUntil", System.currentTimeMillis() + LOCATION_PRIMARY_BACKOFF_MS).apply();
                } else {
                    sendLog("Primary VPN location provider failed; trying fallback: " + safeMessage(primaryError));
                }
            }
        }
        if (geo == null) geo = new JSONObject(socksHttpGet(socksAddress, "ipwho.is", "/" + address));
        String country = countryCodeFromJson(geo);
        if (country.isEmpty()) throw new GoolExitException(getString(R.string.service_gool_country_unavailable));
        String location = locationFromJson(geo);
        stateStore.edit().putInt(LOCATION_CACHE_VERSION_KEY, LOCATION_CACHE_VERSION)
                .putString(LOCATION_CACHE_VALUE_KEY, location)
                .putString(LOCATION_CACHE_IP_KEY, address)
                .putString(LOCATION_CACHE_COUNTRY_KEY, country)
                .putLong(LOCATION_CACHE_TIME_KEY, System.currentTimeMillis()).apply();
        return new GoolExit(address, country, location);
    }

    static String traceValue(String trace, String key) {
        if (trace == null || key == null || key.isEmpty()) return "";
        String prefix = key + "=";
        for (String line : trace.split("\\r?\\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    static String countryCodeFromJson(JSONObject geo) {
        if (geo == null) return "";
        if (geo.optBoolean("error", false) || (geo.has("success") && !geo.optBoolean("success", true))) return "";
        return normalizedCountryCode(
                geo.optString("country_code", geo.optString("countryCode", "")),
                geo.optString("country", geo.optString("countryCode", "")));
    }

    /**
     * dev.020 CHANGE 6: strictly valid ISO 3166-1 alpha-2 or empty. Cloudflare's trace returns
     * {@code loc=T1} for Tor exits: "T1" is NOT a country code, yet the old length-2 check
     * accepted it and {@code countryFlag("T1")} rendered a broken flag + "T1" on Home LOCATION.
     * Every code that is not exactly two ASCII letters is rejected (T1, XX digits, junk); the
     * A-Z-only test matches the flag renderer's own requirement, so a returned code can always
     * be rendered as a flag. A country name fallback remains for providers that answer with
     * "Iran" (uppercase name equality), but a name of length 2 that is not two letters is never
     * accepted.
     */
    static String normalizedCountryCode(String countryCode, String countryName) {
        String code = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.US);
        if (isIsoAlpha2(code)) return code;
        String name = countryName == null ? "" : countryName.trim().toUpperCase(Locale.US);
        if ("IRAN".equals(name)) return "IR";
        return isIsoAlpha2(name) ? name : "";
    }

    /** Exact ISO 3166-1 alpha-2 shape: two ASCII letters and nothing else (rejects T1). */
    static boolean isIsoAlpha2(String code) {
        return code != null && code.length() == 2
                && code.charAt(0) >= 'A' && code.charAt(0) <= 'Z'
                && code.charAt(1) >= 'A' && code.charAt(1) <= 'Z';
    }

    /**
     * dev.020 CHANGE 6: a Cloudflare trace value that describes the Tor network rather than a
     * country ("T1", and defensively "T2"/"XX" if Cloudflare ever reports another special-use
     * marker). Such a value must never become a flag; the real Tor exit country has to be
     * resolved separately from the trace.
     */
    static boolean isNonCountryMarker(String code) {
        return code != null && (code.equals("T1") || code.equals("T2") || code.equals("XX"));
    }

    static boolean providerShouldBackOff(String message) {
        return message != null && (message.contains("HTTP 403") || message.contains("HTTP 429"));
    }

    private static final class GoolExit {
        final String address;
        final String countryCode;
        final String location;

        GoolExit(String address, String countryCode, String location) {
            this.address = address;
            this.countryCode = countryCode;
            this.location = location == null || location.isEmpty() ? countryCode : location;
        }
    }

    private static final class GoolExitException extends Exception {
        GoolExitException(String message) { super(message); }
        GoolExitException(String message, Throwable cause) { super(message, cause); }
    }

    private static class ProxyStartupException extends Exception {
        ProxyStartupException(String message) { super(message); }
        ProxyStartupException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class ProxyPortException extends ProxyStartupException {
        ProxyPortException(String message) { super(message); }
        ProxyPortException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Turn on RFC 2818 hostname checking and send {@code host} as SNI.
     *
     * <p>A bare {@link SSLSocket} validates the certificate chain but, unlike
     * {@link javax.net.ssl.HttpsURLConnection}, does <em>not</em> check that the
     * certificate was issued for the host being contacted until the endpoint
     * identification algorithm is set. Without it, any certificate a trusted CA would
     * sign for any domain is accepted, so whatever sits in the proxy path could
     * impersonate these location services and dictate the exit address this app
     * reports back to the user.
     */
    static SSLParameters httpsIdentityParameters(SSLParameters parameters, String host) {
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        // Ask for the certificate belonging to this host rather than whatever the peer
        // serves by default. SNI carries domain names only (RFC 6066 section 3), and the
        // JDK does not enforce that, so IP literals are filtered out here. Endpoint
        // identification above ties the certificate to `host` either way.
        if (!isIpLiteral(host)) {
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
        }
        return parameters;
    }

    static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || parseIpv4Address(host) != null;
    }

    /** Layer verified TLS for {@code host} over an already-connected {@code tunnel}. */
    static SSLSocket verifiedTlsSocket(SSLSocketFactory factory, Socket tunnel, String host, int port, int timeoutMs) throws IOException {
        return verifiedTlsSocket(factory, tunnel, host, port, timeoutMs, null);
    }

    private static SSLSocket verifiedTlsSocket(SSLSocketFactory factory, Socket tunnel, String host, int port, int timeoutMs, ProbeCancellation cancellation) throws IOException {
        SSLSocket ssl = (SSLSocket) factory.createSocket(tunnel, host, port, true);
        try {
            if (cancellation != null) cancellation.track(ssl);
            ssl.setSSLParameters(httpsIdentityParameters(ssl.getSSLParameters(), host));
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException rejected) {
            try { ssl.close(); } catch (IOException ignored) { }
            throw rejected;
        }
    }

    private String socksHttpGet(String socksAddress, String host, String path) throws Exception {
        return socksHttpGet(socksAddress, host, path, 10_000);
    }

    private String socksHttpGet(String socksAddress, String host, String path, int timeoutMs) throws Exception {
        return proxyHttpGet(socksAddress, host, path, timeoutMs, false);
    }

    private String proxyHttpGet(String proxyAddress, String host, String path, int timeoutMs, boolean viaHttp) throws Exception {
        return proxyHttpGet(proxyAddress, host, path, timeoutMs, viaHttp, null);
    }

    private String proxyHttpGet(String proxyAddress, String host, String path, int timeoutMs, boolean viaHttp, ProbeCancellation cancellation) throws Exception {
        try (Socket tunnel = viaHttp ? ProxyMode.openHttpTunnel(proxyAddress, host, 443, timeoutMs, cancellation)
                : openSocksTunnel(proxyAddress, host, 443, timeoutMs, cancellation)) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket ssl = verifiedTlsSocket(factory, tunnel, host, 443, timeoutMs, cancellation)) {
                OutputStream output = ssl.getOutputStream();
                output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nAccept: application/json\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.UTF_8));
                String line;
                int status = 0;
                StringBuilder body = new StringBuilder();
                boolean headers = true;
                while ((line = reader.readLine()) != null && body.length() < 8192) {
                    if (headers) {
                        if (line.startsWith("HTTP/")) status = Integer.parseInt(line.split(" ", 3)[1]);
                        if (line.isEmpty()) headers = false;
                    } else body.append(line).append('\n');
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("Location service returned HTTP " + status);
                return body.toString();
            }
        }
    }

    private static String locationFromJson(JSONObject geo) {
        if (geo.optBoolean("error", false) || (geo.has("success") && !geo.optBoolean("success", true))) return "";
        String city = geo.optString("city", "").trim();
        if (city.isEmpty()) city = geo.optString("town", geo.optString("locality", "")).trim();
        String country = countryCodeFromJson(geo);
        if (!city.isEmpty() && !country.isEmpty()) return countryFlag(country) + " " + city;
        if (!country.isEmpty()) return countryFlag(country) + " " + country;
        return "";
    }

    static boolean providerMatchesAddress(JSONObject geo, String expectedAddress) {
        if (geo == null || expectedAddress == null || expectedAddress.isEmpty()) return true;
        String reported = geo.optString("ip", geo.optString("query", "")).trim();
        return addressesMatch(expectedAddress, reported);
    }

    static boolean addressesMatch(String expectedAddress, String reportedAddress) {
        if (expectedAddress == null || expectedAddress.isEmpty()) return true;
        return reportedAddress == null || reportedAddress.isEmpty() || expectedAddress.equalsIgnoreCase(reportedAddress);
    }

    private static String countryFlag(String country) {
        // dev.020 CHANGE 6: only a strict ISO alpha-2 code becomes a flag. "T1" and any
        // non-two-letter marker return an empty flag so the value degrades to "unavailable"
        // rather than rendering a broken flag.
        if (!isIsoAlpha2(country)) return "";
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
        if ("gool".equals(protocol)) return "Gool";
        if ("mim".equals(protocol)) return "MASQUE-in-MASQUE";
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
            writer.write("  mtu: " + effectiveMtu(request) + "\n");
            writer.write("  ipv4: 198.18.0.1\n");
            // Always give HEV the IPv6 tunnel address. The TUN always carries IPv6 routes now, so
            // without this HEV cannot process those packets and they are silently dropped; with it
            // every IPv6 flow becomes a SOCKS5 CONNECT that either reaches the core's IPv6 exit or
            // fails fast enough for the client to fall back to IPv4.
            writer.write("  ipv6: 'fc00::1'\n");
            writer.write("  icmp: 'reply'\n");
            writer.write("socks5:\n");
            writer.write("  address: '" + yamlEscape(socks.host) + "'\n");
            writer.write("  port: " + socks.port + "\n");
            // The Psiphon helper's local SOCKS serves CONNECT and standard UDP ASSOCIATE; the
            // device's DNS and other UDP flows ride the same UDP relay in Chain mode as they do
            // through the base listener, so both topologies keep the native 'udp' mode. (HEV's
            // 'tcp' mode is not an option here: it issues a private forward-UDP command the
            // Psiphon server rejects, which the dev.016 device validation demonstrated.)
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
        // 2000::/3 is the whole of currently allocated global IPv6 unicast, so this captures every
        // routable IPv6 destination while link-local and ULA traffic stays on the local network,
        // which is what bypass-local means for IPv4 as well.
        builder.addRoute("2000::", 3);
    }

    /**
     * IPv6 always enters the tunnel, so this only records what is actually known about the upstream
     * leg - and it must not overstate it. The two inputs are the requested core endpoint family and
     * whether the underlying network carries a global IPv6 address; neither determines whether the
     * exit can emit IPv6, because HEV hands every flow to the core as a SOCKS5 CONNECT carrying a
     * host <em>name</em>, and the core resolves and egresses that on its own upstream. On this device
     * an IPv4-only cellular link still produced IPv6 egress: Android's Private DNS reached
     * 2606:4700:4700::1001 on port 853 from the tun0 address fc00::1, and the browser's exit address
     * was the WARP IPv6 2a09:bac5:5274:505::80:c2. The previous wording claimed "not forwarded;
     * IPv4-only mode" in exactly that situation, which would tell an auditor IPv6 is black-holed
     * when it is in fact carried and translated by the exit.
     *
     * <p>What is guaranteed either way is the part that matters for leaks: the route table owns
     * {@code 2000::/3}, so an IPv6 flow can only ever reach the core, never the underlying
     * interface. If the exit has no IPv6 the CONNECT fails fast and Happy Eyeballs falls back to
     * IPv4 on a reset instead of stalling on a black hole.
     */
    private void logIpv6Decision(boolean captured, boolean upstreamIpv6) {
        if (!captured) {
            sendLog("IPv6 tunnel routing disabled");
            return;
        }
        sendLog("IPv6 captured into the tunnel (2000::/3); underlying network global IPv6="
                + (upstreamIpv6 ? "yes" : "no")
                + "; egress family is decided by the Aether core per SOCKS5 CONNECT");
    }

    static boolean ipv6ModeRequested(String ipMode) {
        return "v6".equals(ipMode) || "both".equals(ipMode);
    }

    private boolean upstreamHasGlobalIpv6() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) return false;
        for (Network network : availableNetworks) {
            if (hasGlobalIpv6(manager.getLinkProperties(network))) return true;
        }
        return hasGlobalIpv6(manager.getLinkProperties(manager.getActiveNetwork()));
    }

    private static boolean hasGlobalIpv6(LinkProperties properties) {
        if (properties == null) return false;
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress candidate = link.getAddress();
            if (!(candidate instanceof Inet6Address)) continue;
            if (candidate.isAnyLocalAddress() || candidate.isLoopbackAddress()
                    || candidate.isLinkLocalAddress() || candidate.isSiteLocalAddress()) continue;
            return true;
        }
        return false;
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
        enforceConnectDeadline();
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
        lastTrafficTx = tx;
        lastTrafficRx = rx;
        lastTrafficSampleAt = System.currentTimeMillis();
        long previousWidgetPing = stateStore.getLong("ping", Long.MIN_VALUE);
        long previousWidgetTx = stateStore.getLong("tx", Long.MIN_VALUE);
        long previousWidgetRx = stateStore.getLong("rx", Long.MIN_VALUE);
        boolean changed = previousWidgetPing != lastPing || previousWidgetTx != tx || previousWidgetRx != rx;
        if (changed) {
            stateStore.edit().putLong("ping", lastPing).putLong("tx", tx).putLong("rx", rx).apply();
            long now = System.currentTimeMillis();
            if (previousWidgetPing != lastPing || now - lastWidgetUpdateAt >= 6_000L) {
                lastWidgetUpdateAt = now;
                AethonWidgetProvider.update(this);
            }
        }
        maybeCheckTunnelHealth();
        // dev.020 CHANGE 9: a connected telemetry tick is also the accounting checkpoint (the
        // tick already runs every 2s; the tracker persists only whole accumulated seconds, so
        // this is one small write per tick while connected and none while disconnected - no
        // wasteful per-second disk writes; the Home display itself updates once per second
        // from the broadcast without any disk access).
        if (connectedTime != null && "connected".equals(currentState)) connectedTime.checkpoint();
        Intent intent = new Intent(ACTION_STATS).setPackage(getPackageName());
        intent.putExtra("tx", tx).putExtra("rx", rx).putExtra("ping", lastPing).putExtra("connectedAt", connectedAt)
                .putExtra("dailySeconds", connectedTime == null ? 0L : connectedTime.todaySeconds());
        if (changed || System.currentTimeMillis() - lastStatsBroadcastAt >= 5_000L) {
            lastStatsBroadcastAt = System.currentTimeMillis();
            sendBroadcast(intent, INTERNAL_PERMISSION);
        }
    }

    /** True when the health prober should run for the given published state. */
    static boolean healthProbeApplies(String state) {
        return "connected".equals(state) || "reconnecting".equals(state);
    }

    /**
     * The prober deliberately also runs while the state is "reconnecting". Recovery only published
     * "connected" again from {@link #monitorAether}'s restore branch, so a core that came back by
     * any other route left a working tunnel reported as still connecting, with the prober itself
     * switched off by the old "connected"-only guard and therefore unable to notice or correct it.
     */
    private void maybeCheckTunnelHealth() {
        long now = System.currentTimeMillis();
        boolean connected = "connected".equals(currentState);
        boolean recovering = !connected && healthProbeApplies(currentState) && connectionEstablished;
        if ((!connected && !recovering) || networkUnavailable || now - lastHealthCheckAt < HEALTH_CHECK_INTERVAL_MS) return;
        Intent request = activeRequest;
        boolean privacy = request != null && RuntimeTrafficPlan.privacyEnabled(AndroidCoreSettings.fromIntent(request));
        if (recovering && privacy) return;
        // Observed traffic only stands in for a probe once the state is settled; while recovering the
        // probe is the evidence that decides whether the tunnel is serving again.
        if (connected && !privacy && !ProxyMode.enabled(request == null ? "" : value(request, "connectionMode", "vpn"))
                && lastTrafficSampleAt > 0 && (lastTrafficTx != lastHealthTx || lastTrafficRx != lastHealthRx)
                && now - lastSuccessfulHealthAt < MAX_HEALTH_VERIFICATION_GAP_MS) {
            lastHealthTx = lastTrafficTx;
            lastHealthRx = lastTrafficRx;
            lastHealthCheckAt = now;
            return;
        }
        if (!healthCheckRunning.compareAndSet(false, true)) return;
        lastHealthCheckAt = now;
        sendLog("Performance ping_probe_started_after_ready=" + Math.max(0L, System.currentTimeMillis() - connectedAt) + "ms");
        try {
            worker.execute(() -> {
                Process provingProcess = aetherProcess;
                long provingSession = generation.get();
                ProbeCancellation privacyProbe = new ProbeCancellation();
                try {
                    activePrivacyHealthProbe = privacyProbe;
                    if (request == null || !isCurrentSession(request, provingSession) || !active) return;
                    String socks = value(request, "socks", "127.0.0.1:1819");
                    if (ProxyMode.enabled(value(request, "connectionMode", "vpn"))) {
                        if (!proxyListenersRespond(provingProcess)) {
                            if (!recovering) requestCoreRecovery(getString(R.string.service_proxy_not_ready,
                                    ProxyMode.SOCKS_ADDRESS, ProxyMode.HTTP_ADDRESS));
                            return;
                        }
                        try (Socket ignored = ProxyMode.openHttpTunnel(ProxyMode.HTTP_ADDRESS, "1.1.1.1", 443, 4_000)) {
                            // A live HTTP parser must also be able to open a tunneled stream.
                        }
                    }
                    lastPing = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
                    if (privacy) verifyPrivacyHealth(request, provingSession, privacyProbe);
                    if (!isCurrentSession(request, provingSession) || provingProcess != aetherProcess
                            || provingProcess == null || !provingProcess.isAlive()) return;
                    lastSuccessfulHealthAt = System.currentTimeMillis();
                    consecutiveHealthFailures = 0;
                    sendLog("Performance ping_available_after_ready=" + Math.max(0L, System.currentTimeMillis() - connectedAt) + "ms value=" + lastPing);
                    if (recovering) confirmRecoveredTunnel(provingProcess);
                } catch (Throwable error) {
                    if (!isCurrentSession(request, provingSession) || provingProcess != aetherProcess) return;
                    lastPing = -1;
                    // monitorAether owns the retry schedule while a recovery is in flight; counting these
                    // failures as well would race a second teardown into its backoff.
                    if (recovering) return;
                    if (++consecutiveHealthFailures >= 3) {
                        consecutiveHealthFailures = 0;
                        requestCoreRecovery(getString(R.string.service_tunnel_unresponsive));
                    }
                } finally {
                    privacyProbe.close();
                    if (activePrivacyHealthProbe == privacyProbe) activePrivacyHealthProbe = null;
                    healthCheckRunning.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            // The flag is released by the lambda's finally block, so a dispatch that never runs the
            // lambda would latch it true and silence every future probe for the life of the service.
            healthCheckRunning.set(false);
            Log.w(TAG, "Health probe could not be dispatched", rejected);
        }
    }

    private void verifyPrivacyHealth(Intent request, long session, ProbeCancellation cancellation) throws Exception {
        RuntimeTrafficPlan.Announcements announcements = privacyAnnouncements;
        if (announcements == null || !announcements.allAnnounced()) throw new IOException("Core privacy listeners are not ready");
        Map<String, Object> settings = ProxyMode.settings(value(request, "connectionMode", "vpn"),
                AndroidCoreSettings.fromIntent(request));
        for (RuntimeTrafficPlan.Endpoint endpoint : RuntimeTrafficPlan.endpoints(settings,
                value(request, "socks", ProxyMode.VPN_SOCKS_DEFAULT))) {
            if (!endpoint.privacy) continue;
            Exception failure = null;
            for (String[] target : TRAFFIC_READY_TARGETS) {
                if (!isCurrentSession(request, session)) throw new InterruptedException("Privacy health check cancelled");
                try {
                    String body = proxyHttpGet(endpoint.address, target[0], target[1],
                            TRAFFIC_READY_TIMEOUT_MS, endpoint.http, cancellation);
                    if (body.trim().isEmpty()) throw new IOException("Privacy HTTPS response was empty");
                    failure = null;
                    break;
                } catch (IOException error) { failure = error; }
            }
            if (failure != null) throw new IOException(endpoint.name + " cannot carry HTTPS traffic", failure);
        }
        if (announcements != privacyAnnouncements || !announcements.allAnnounced())
            throw new IOException("Core privacy listeners changed during validation");
    }

    /** Publishes "connected" again once a probe has proven the recovered tunnel carries traffic. */
    private void confirmRecoveredTunnel(Process provenProcess) {
        if (stopping || !active || networkUnavailable || !connectionEstablished) return;
        if (!"reconnecting".equals(currentState)) return;
        Process process = aetherProcess;
        if (process == null || process != provenProcess || !process.isAlive()) return;
        recoveryRestartPending.set(false);
        updateState("connected", getString(R.string.service_restored));
        updateNotification(getString(R.string.service_restored));
        sendLog("Tunnel health verified after recovery; connection published as active again");
    }

    private void requestNetworkRecovery() {
        long now = System.currentTimeMillis();
        if (now - lastNetworkRecoveryAt < NETWORK_RECOVERY_DEBOUNCE_MS) return;
        lastNetworkRecoveryAt = now;
        requestCoreRecovery(getString(R.string.service_network_restored));
    }

    private void requestCoreRecovery(String message) {
        Intent request = activeRequest;
        if (!active || stopping || request == null || !request.getBooleanExtra("quickReconnect", true)) return;
        if (!connectionEstablished || !recoveryRestartPending.compareAndSet(false, true)) return;
        updateState("reconnecting", message);
        updateNotification(message);
        worker.execute(() -> {
            synchronized (runtimeLock) {
                Process process = aetherProcess;
                if (process != null && process.isAlive()) {
                    process.destroy();
                    try {
                        if (!process.waitFor(750, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    }
                }
            }
            if (active && !stopping) {
                sendLog("Tunnel recovery requested: " + message);
            }
        });
    }

    private void stopConnection(boolean userInitiated) {
        long teardownStarted = SystemClock.elapsedRealtime();
        stopping = true;
        connectDeadlineAt = 0L;
        connectDeadlineSession = -1L;
        connectTimedOutSession = -1L;
        stopRuntime();
        // A session that is being torn down deliberately must not leave a core behind; the reaper
        // covers the case where destroy() returned before the child had actually exited.
        reapOrphanedCores();
        boolean coreStopped = aetherProcess == null || !aetherProcess.isAlive();
        active = false;
        connectedAt = 0;
        currentEndpoint = "";
        selectedProtocol = "";
        smartSelected = false;
        locationLookupSequence.incrementAndGet();
        activeRequest = null;
        stopLanSharing();
        connectionEstablished = false;
        recoveryRestartPending.set(false);
        lastPing = -1;
        stateStore.edit().putLong("ping", -1L).apply();
        lastSuccessfulHealthAt = 0;
        lastHealthCheckAt = 0;
        lastHealthTx = 0;
        lastHealthRx = 0;
        consecutiveHealthFailures = 0;
        if (userInitiated) {
            updateState(coreStopped ? "disconnected" : "error",
                    getString(coreStopped ? R.string.service_disconnected : R.string.service_core_still_stopping));
            sendLog("Performance disconnect teardown=" + (SystemClock.elapsedRealtime() - teardownStarted) + "ms");
        }
        if (userInitiated) {
            cancelProtectionLostAlert();
            stopForeground(STOP_FOREGROUND_REMOVE);
            // A disconnected service has no work left to supervise. Stopping the service here
            // also guarantees onDestroy() runs, which performs a final idempotent runtime stop
            // and prevents a stale monitor from retaining the Aether process after disconnect.
            stopSelf();
        }
    }

    /**
     * Stops the HEV bridge off {@code runtimeLock} and with a bound on how long the caller waits.
     *
     * <p>{@code TProxyStopService()} joins the tunnel worker, so it is a native call of unbounded
     * duration. Running it under runtimeLock - as this used to - meant a worker that did not unwind
     * blocked the rest of the teardown behind it: the TUN descriptor was never closed, the core was
     * never killed, the "disconnect teardown=" line was never emitted, and because the lifecycle
     * executor is single-threaded, every later Connect queued behind the wedged Disconnect.
     *
     * <p>Overrunning the bound is not treated as success. The thread is kept in
     * {@link #pendingBridgeStop} so {@link #establishVpn} refuses to start a new bridge on top of a
     * worker that has not finished - upstream {@code TProxyStartService} is a no-op while one
     * exists, which would otherwise publish a tunnel that carries nothing.
     *
     * @return true when the bridge stopped within {@link #BRIDGE_STOP_TIMEOUT_MS}
     */
    private boolean stopBridge() {
        Thread previous = pendingBridgeStop;
        if (previous != null && !previous.isAlive()) pendingBridgeStop = null;
        Thread stopper = new Thread(() -> {
            try { TProxyService.TProxyStopService(); }
            catch (Throwable error) { Log.w(TAG, "Could not stop HEV", error); }
        }, "aether-bridge-stop");
        stopper.setDaemon(true);
        pendingBridgeStop = stopper;
        long started = SystemClock.elapsedRealtime();
        stopper.start();
        try { stopper.join(BRIDGE_STOP_TIMEOUT_MS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!stopper.isAlive()) {
            pendingBridgeStop = null;
            return true;
        }
        sendLog("HEV bridge stop did not finish within " + BRIDGE_STOP_TIMEOUT_MS
                + "ms (waited " + (SystemClock.elapsedRealtime() - started)
                + "ms); releasing the tunnel and the core anyway");
        return false;
    }

    /**
     * Waits for an overrunning bridge stop before a new bridge is started.
     *
     * @return true when no stop is outstanding any more
     */
    private boolean awaitBridgeHandover() {
        Thread outstanding = pendingBridgeStop;
        if (outstanding == null || !outstanding.isAlive()) { pendingBridgeStop = null; return true; }
        sendLog("A previous HEV bridge stop is still finishing; waiting before starting a new one");
        try { outstanding.join(BRIDGE_HANDOVER_TIMEOUT_MS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        if (outstanding.isAlive()) return false;
        pendingBridgeStop = null;
        return true;
    }

    private void stopRuntime() {
        boolean bridgeUp;
        synchronized (runtimeLock) {
            bridgeUp = bridgeStarted;
            bridgeStarted = false;
        }
        // Deliberately outside runtimeLock: see stopBridge().
        if (bridgeUp) stopBridge();
        synchronized (runtimeLock) {
            try { if (vpnInterface != null) vpnInterface.close(); }
            catch (Exception ignored) { }
            vpnInterface = null;
            stopAetherOnly();
        }
    }

    private void stopAetherOnly() {
        ProbeCancellation[] probes = activeTrafficProbes;
        if (probes != null) for (ProbeCancellation probe : probes) probe.close();
        ProbeCancellation healthProbe = activePrivacyHealthProbe;
        if (healthProbe != null) healthProbe.close();
        readinessTrace.clear();
        stopPublicRelays();
        synchronized (runtimeLock) {
            Process process = aetherProcess;
            proxyListeners = null;
            privacyAnnouncements = null;
            if (process != null) {
                if (CoreProcessGuard.stop(process, CORE_STOP_GRACE_MS)) {
                    aetherProcess = null;
                    reapOrphanedCores();
                }
                else sendLog(getString(R.string.service_core_still_stopping));
            }
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
        String previous = currentState;
        currentState = state;
        currentMessage = message == null ? "" : message;
        // dev.020 CHANGE 9: the daily connected-time accounting follows the real state machine.
        // Only the genuine Connected state contributes duration; every other state (including
        // "reconnecting", "disconnecting", failures) stops the clock - failed connection
        // attempts and time between sessions are never counted. A transition that stays within
        // Connected (repeated connected events) is idempotent in the tracker.
        if (connectedTime != null) {
            if ("connected".equals(state) && !"connected".equals(previous)) connectedTime.onConnected();
            else if (!"connected".equals(state) && "connected".equals(previous)) connectedTime.onDisconnected();
        }
        // The tile, widget, and activity all live in this process, so a live snapshot lets them read
        // the state the service is actually in rather than whatever SharedPreferences last flushed.
        LIVE_STATE.set(state);
        stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", currentEndpoint)
                .putString("connectionMode", activeRequest == null ? "" : value(activeRequest, "connectionMode", "vpn"))
                .putString("selectedProtocol", selectedProtocol).putBoolean("smartSelected", smartSelected).apply();
        sendStatus(currentState, currentMessage);
        AethonTileService.requestUpdate(this);
        AethonWidgetProvider.update(this);
    }

    private void sendStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra("state", state).putExtra("message", message).putExtra("endpoint", currentEndpoint)
                .putExtra("statusAtElapsed", SystemClock.elapsedRealtime())
                .putExtra("selectedProtocol", selectedProtocol)
                .putExtra("smartSelected", smartSelected);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private boolean startLanSharing(int port, String socks) {
        boolean started = lanProxy.start(port, socks);
        lanSharingActive = started;
        SharedPreferences.Editor editor = stateStore.edit();
        if (started) {
            editor.putString("lanAddress", lanProxy.address())
                    .putInt("lanPort", lanProxy.port())
                    .putString("lanUsername", lanProxy.username())
                    .putString("lanPassword", lanProxy.password())
                    .commit();
            sendLog("LAN SOCKS5 sharing active on " + lanProxy.address() + ":" + lanProxy.port());
            // The listener now requires RFC 1929 auth, so the session credentials have to reach the
            // user somehow; the log page and the LAN card are the two places they already look.
            sendLog("LAN SOCKS5 credentials for this session: " + lanProxy.username() + " / " + lanProxy.password());
        } else {
            editor.remove("lanAddress").remove("lanPort").remove("lanUsername").remove("lanPassword").commit();
            sendLog("LAN SOCKS5 sharing could not start: no reachable WiFi/hotspot IPv4 address");
        }
        return started;
    }

    private void stopLanSharing() {
        boolean wasActive = lanSharingActive;
        lanSharingActive = false;
        lanProxy.stop();
        stateStore.edit().remove("lanAddress").remove("lanPort").remove("lanUsername").remove("lanPassword").commit();
        // Only report a real state change; every disconnect used to log a stop for a listener that
        // had never been started, which buried the lines that matter during a failure analysis.
        if (wasActive) sendLog("LAN SOCKS5 sharing stopped");
    }

    private void refreshLanBinding() {
        if (!active || stopping || !"connected".equals(currentState)
                || !getSharedPreferences("aether", MODE_PRIVATE).getBoolean("lanEnabled", false)) return;
        String socks = activeRequest == null ? "127.0.0.1:1819" : value(activeRequest, "socks", "127.0.0.1:1819");
        int port = stateStore.getInt("lanPort", 18190);
        if (!lanProxy.matchesAddress()) {
            lanProxy.stop();
            if (startLanSharing(port, socks)) sendStatus(currentState, currentMessage);
        }
    }

    private void sendLog(String line) {
        if (line == null || line.trim().isEmpty()) return;
        if (activeRequest != null) line = CoreSettings.redact(line, AndroidCoreSettings.fromIntent(activeRequest));
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
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
        // A dropped tunnel has to be visible even though the ongoing low-importance notification is
        // removed on failure, so the alert lives on its own default-importance channel.
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL_ID,
                getString(R.string.notification_alert_channel), NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription(getString(R.string.notification_alert_channel_summary));
        manager.createNotificationChannel(alerts);
    }

    /**
     * Kill switch off means Android has already restored direct routing, so silence would leave the
     * user believing they are still protected. PROMPT phase 13 allows either blocking traffic or
     * clearly notifying the user; this is the notify half of that contract.
     */
    private void notifyProtectionLost(String reason) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification alert = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_aethon_mono)
                .setContentTitle(getString(R.string.service_protection_lost_title))
                .setContentText(getString(R.string.service_protection_lost, reason))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.service_protection_lost, reason)))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(content)
                .build();
        getSystemService(NotificationManager.class).notify(ALERT_NOTIFICATION_ID, alert);
        sendLog("User alerted that traffic is no longer protected: " + reason);
    }

    private void cancelProtectionLostAlert() {
        getSystemService(NotificationManager.class).cancel(ALERT_NOTIFICATION_ID);
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
        lifecycle.execute(() -> stopConnection(true));
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopping = true;
        connectDeadlineAt = 0L;
        connectDeadlineSession = -1L;
        stopRuntime();
        // The core is a child of this process, so a destroyed service that leaves one running keeps
        // the SOCKS port bound and makes the next Connect fail against a dead tunnel.
        reapOrphanedCores();
        flushLogs();
        synchronized (logLock) {
            stateStore.edit().putString("logs", logHistory.toString()).apply();
        }
        if (VpnConnectionController.canDisconnect(currentState)) {
            currentState = "disconnected";
            currentMessage = getString(R.string.service_disconnected);
            stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", "").apply();
        }
        // No instance is left to answer for the live state; the tile falls back to the persisted
        // value combined with its own ConnectivityManager cross-check.
        LIVE_STATE.set(null);
        AethonTileService.requestUpdate(this);
        telemetry.shutdownNow();
        lifecycle.shutdownNow();
        worker.shutdownNow();
        try { connectivityManager.unregisterNetworkCallback(networkCallback); }
        catch (RuntimeException error) { Log.w(TAG, "Network callback was already unregistered", error); }
        super.onDestroy();
    }

    private static String value(Intent intent, String key, String fallback) {
        String result = intent.getStringExtra(key);
        return result == null || result.trim().isEmpty() ? fallback : result.trim();
    }

    // --- dev.020 CHANGE 9: connected-time tracker platform adapters ---------------------------

    /** The tracker's clock bound to the platform's monotonic and calendar sources. */
    private static ConnectedTimeTracker.Clock connectedTimeClock() {
        return new ConnectedTimeTracker.Clock() {
            @Override public long elapsedRealtimeMillis() { return SystemClock.elapsedRealtime(); }
            @Override public java.time.LocalDate localDate() { return java.time.LocalDate.now(); }
            @Override public long wallClockMillis() { return System.currentTimeMillis(); }
        };
    }

    /** The tracker's persistence bound to the dedicated "connected_time" SharedPreferences. */
    private ConnectedTimeTracker.Store connectedTimeStore() {
        SharedPreferences prefs = getSharedPreferences(ConnectedTimeTracker.PREFS, MODE_PRIVATE);
        return new ConnectedTimeTracker.Store() {
            @Override public String readDay() { return prefs.getString(ConnectedTimeTracker.KEY_DAY, ""); }
            @Override public long readSeconds() { return prefs.getLong(ConnectedTimeTracker.KEY_SECONDS, 0L); }
            @Override public long readLastKnownElapsed() { return prefs.getLong(ConnectedTimeTracker.KEY_LAST_ELAPSED, 0L); }
            @Override public void write(String day, long seconds, long lastKnownElapsed) {
                prefs.edit()
                        .putString(ConnectedTimeTracker.KEY_DAY, day)
                        .putLong(ConnectedTimeTracker.KEY_SECONDS, seconds)
                        .putLong(ConnectedTimeTracker.KEY_LAST_ELAPSED, lastKnownElapsed)
                        .apply();
            }
        };
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static long elapsedSinceRequest(Intent request) {
        long requested = request == null ? 0L : request.getLongExtra("requestedAtElapsed", 0L);
        return requested <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - requested);
    }

    private static String yamlEscape(String value) { return value.replace("'", "''"); }

    private static int effectiveMtu(Intent request) {
        int configured = request == null ? DEFAULT_MTU : request.getIntExtra("mtu", DEFAULT_MTU);
        String protocol = request == null ? ConnectionDefaults.PROTOCOL
                : value(request, "protocol", ConnectionDefaults.PROTOCOL);
        return effectiveMtu(protocol, configured);
    }

    static int effectiveMtu(String protocol, int configured) {
        int transportCap;
        if ("gool".equals(protocol)) transportCap = NESTED_WIREGUARD_MTU_CAP;
        else if ("mim".equals(protocol)) transportCap = MIN_MTU;
        else if ("wg".equals(protocol)) transportCap = WIREGUARD_MTU_CAP;
        else transportCap = MASQUE_MTU_CAP;
        return Math.max(MIN_MTU, Math.min(transportCap, configured));
    }

    private static boolean usesWarp(Intent request) {
        return PsiphonConfiguration.usesWarp(value(request, "psiphonMode", "off"));
    }

    private static String reliableEndpoint(Intent request) {
        if (!usesWarp(request)) return "";
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
            double handshakeScore = 25.0 * clamp(1.0 - handshakeMs / (double) SMART_PROTOCOL_TIMEOUT_MS);
            double latencyScore = 15.0 * clamp(1.0 - latencyMs / 2_000.0);
            double dnsScore = 10.0 * clamp(1.0 - dnsMs / 2_000.0);
            double stabilityScore = 10.0 * stableProbes / Math.max(1, attempts);
            return new SmartResult(protocol, true, handshakeMs, latencyMs, dnsMs, stableProbes, attempts, 40.0 + handshakeScore + latencyScore + dnsScore + stabilityScore);
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
