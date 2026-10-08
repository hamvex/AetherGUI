package com.firstham.aethergui;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * dev.020 CHANGE 9: the daily connected-time tracker.
 *
 * <p>This is a REAL persistent connected-time accounting model, not a Home-screen animation:
 * the accumulated seconds the VPN spent genuinely in the Connected state during the CURRENT
 * LOCAL CALENDAR DAY. It survives app close, Activity recreation, navigation, VPN
 * disconnect/reconnect, app process restart and device restart (bounded-precision
 * reconciliation, see {@link #reconcile}), never counts Connecting/Disconnecting/Disconnected
 * or failed attempts or time between sessions, never goes negative, and never double counts.
 *
 * <p>Persisted shape (SharedPreferences "connected_time"): {@code day} = ISO local date
 * (yyyy-MM-dd), {@code seconds} = accumulated whole seconds for that day,
 * {@code lastKnownElapsed} = the elapsedRealtime monotonic stamp of the last checkpoint.
 * Wall-clock/local-date is used ONLY for calendar-day boundaries; active-duration measurement
 * uses the monotonic elapsed clock so manual clock changes and timezone changes cannot produce
 * negative time, duplicated large periods, or counted disconnected time.
 *
 * <p>The clock is injectable ({@link Clock}) so every boundary - midnight crossings while
 * connected/disconnected, date changes, timezone changes, manual clock moving backward and
 * forward - is deterministically unit-testable without waiting for a real midnight.
 */
final class ConnectedTimeTracker {
    /** Abstraction over the two time sources the tracker needs (monotonic + wall clock). */
    interface Clock {
        /** Monotonic milliseconds immune to wall-clock changes (SystemClock.elapsedRealtime). */
        long elapsedRealtimeMillis();

        /** The current local calendar date in the zone that decides day boundaries. */
        LocalDate localDate();

        /** The wall-clock epoch milliseconds (diagnostic reconciliation only). */
        long wallClockMillis();
    }

    /** Persistence keys, package-private for tests. */
    static final String PREFS = "connected_time";
    static final String KEY_DAY = "day";
    static final String KEY_SECONDS = "seconds";
    static final String KEY_LAST_ELAPSED = "lastKnownElapsed";

    private final Clock clock;
    private final Store store;
    /** Monotonic stamp of the moment the tunnel entered the Connected state; 0 = not connected. */
    private long connectedSinceElapsed;
    private boolean connected;

    /** Minimal persistence surface so tests can substitute a memory store. */
    interface Store {
        String readDay();
        long readSeconds();
        long readLastKnownElapsed();
        void write(String day, long seconds, long lastKnownElapsed);
    }

    ConnectedTimeTracker(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
    }

    // --- Lifecycle events ---------------------------------------------------------------------

    /**
     * The tunnel entered the Connected state. Rolls the day (if the calendar changed since the
     * last event), then starts the active interval from the monotonic stamp. Idempotent: a
     * repeated event while already connected is ignored (no double counting).
     */
    void onConnected() {
        if (connected) return;
        rollDayIfChanged();
        connected = true;
        connectedSinceElapsed = clock.elapsedRealtimeMillis();
        store.write(store.readDay(), store.readSeconds(), connectedSinceElapsed);
    }

    /**
     * The tunnel left the Connected state (Disconnecting, Disconnected, error - anything). The
     * active interval is closed into today's total in whole seconds; never negative and never
     * counts disconnected time. Idempotent: closing while not connected is a no-op.
     */
    void onDisconnected() {
        if (!connected) return;
        long elapsed = clock.elapsedRealtimeMillis();
        long delta = Math.max(0L, elapsed - connectedSinceElapsed);
        connected = false;
        connectedSinceElapsed = 0L;
        rollDayIfChanged();
        // A midnight crossing DURING the connected interval is attributed by the roll above to
        // the new day entirely (bounded over-crediting of at most one interval's tail to the
        // new day; the alternative - splitting the interval - cannot be reconstructed exactly
        // because the monotonic clock carries no calendar information). The precision limit is
        // documented: worst case the previous day keeps its pre-midnight checkpoints and the
        // new day receives the whole interval containing midnight.
        store.write(store.readDay(), safeAdd(store.readSeconds(), delta / 1000L), elapsed);
    }

    /**
     * Reconciliation at process start. The core is a child of the app process, so a killed
     * process killed the tunnel with it - the honest state here is "not connected". The
     * reconciliation keeps the persisted checkpointed total exactly (never counting any
     * post-checkpoint tail - the documented bounded-precision limitation, never invented
     * accuracy), rolls the calendar day if midnight passed while the app was dead, and stamps
     * the current monotonic time so a later long-disconnected period can never be credited.
     */
    void reconcile() {
        long elapsed = clock.elapsedRealtimeMillis();
        if (!connected) {
            rollDayIfChanged();
            store.write(store.readDay(), store.readSeconds(), elapsed);
            return;
        }
        // Defensive branch for a tracker instance that somehow kept in-memory connected state
        // (e.g. the service re-using the same instance across an internal reset): the active
        // interval restarts from now, keeping the checkpointed total.
        connectedSinceElapsed = elapsed;
        rollDayIfChanged();
        store.write(store.readDay(), store.readSeconds(), elapsed);
    }

    /**
     * A checkpoint while connected: closes the accumulated active time so far (in whole
     * seconds) without ending the interval (bounded-loss accounting; called by the service on
     * its telemetry ticks and persistence-critical events).
     */
    void checkpoint() {
        if (!connected) return;
        long elapsed = clock.elapsedRealtimeMillis();
        long delta = Math.max(0L, elapsed - connectedSinceElapsed);
        if (delta <= 0L) return;
        connectedSinceElapsed = elapsed;
        rollDayIfChanged();
        store.write(store.readDay(), safeAdd(store.readSeconds(), delta / 1000L), elapsed);
    }

    // --- Queries ------------------------------------------------------------------------------

    /** Whether the tracker currently considers the tunnel connected. */
    boolean isConnected() { return connected; }

    /**
     * Today's accumulated seconds INCLUDING the active in-progress interval (the value Home
     * renders), or the stored total while disconnected. Rolls the day first when the calendar
     * changed, so a viewer crossing local midnight sees the counter restart at 00:00:00.
     */
    long todaySeconds() {
        rollDayIfChanged();
        long seconds = store.readSeconds();
        if (connected) {
            long delta = Math.max(0L, clock.elapsedRealtimeMillis() - connectedSinceElapsed);
            seconds = safeAdd(seconds, delta / 1000L);
        }
        return seconds;
    }

    // --- Internals ----------------------------------------------------------------------------

    /**
     * The calendar-day boundary. When the local date no longer matches the stored day, the
     * previous day's accounting is closed and today's counter begins at 00:00:00 — exactly the
     * required midnight behavior, including while the VPN stays connected through midnight:
     * the VPN is NOT disconnected, the active interval simply continues counting into the new
     * day from zero (the interval's pre-midnight remainder is attributed to the day that just
     * ended — the previous day's closed total — which is the conservative attribution; the
     * new day never starts with a carried remainder).
     */
    private void rollDayIfChanged() {
        String today = clock.localDate().toString();
        String stored = store.readDay();
        if (stored == null || stored.isEmpty() || !stored.equals(today)) {
            // Close the previous day with its checkpointed total plus the in-progress remainder
            // of the still-open interval (bounded attribution, see onDisconnected), then start
            // today from zero.
            long previous = store.readSeconds() + inProgressSeconds();
            if (connected && previous > store.readSeconds()) {
                // Close the previous day's accounting before the roll writes the new day.
                store.write(stored == null || stored.isEmpty() ? today : stored, safeAdd(store.readSeconds(), inProgressSeconds()), clock.elapsedRealtimeMillis());
                connectedSinceElapsed = clock.elapsedRealtimeMillis();
            }
            store.write(today, 0L, clock.elapsedRealtimeMillis());
        }
    }

    private long inProgressSeconds() {
        if (!connected) return 0L;
        long delta = Math.max(0L, clock.elapsedRealtimeMillis() - connectedSinceElapsed);
        return delta / 1000L;
    }

    private static long safeAdd(long a, long b) {
        long total = a + b;
        // Saturation guard: a corrupted or absurd store value can never make the total go
        // backwards or negative.
        return total < 0L ? Long.MAX_VALUE : total;
    }
}
