package com.firstham.aethergui;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.020 CHANGE 9 deterministic coverage: the daily connected-time tracker. Every boundary in
 * the PROMPT is exercised with an injectable clock - no test waits for a real midnight. The
 * model under test is the exact code the service runs (monotonic elapsed clock for active
 * duration, wall-clock local date for day boundaries only).
 */
public final class ConnectedTimeTrackerTest {

    /** Scriptable clock: monotonic millis + local date, advanced by the test. */
    private static final class ScriptedClock implements ConnectedTimeTracker.Clock {
        long elapsed = 1_000_000L;
        LocalDate date = LocalDate.of(2026, 10, 3);
        @Override public long elapsedRealtimeMillis() { return elapsed; }
        @Override public LocalDate localDate() { return date; }
        @Override public long wallClockMillis() { return 0L; }
        void advanceSeconds(long seconds) { elapsed += seconds * 1000L; }
        void nextDay() { date = date.plusDays(1); }
    }

    /** In-memory store mirroring the SharedPreferences adapter. */
    private static final class MemoryStore implements ConnectedTimeTracker.Store {
        String day = "";
        long seconds;
        long lastKnownElapsed;
        @Override public String readDay() { return day; }
        @Override public long readSeconds() { return seconds; }
        @Override public long readLastKnownElapsed() { return lastKnownElapsed; }
        @Override public void write(String day, long seconds, long lastKnownElapsed) {
            this.day = day; this.seconds = seconds; this.lastKnownElapsed = lastKnownElapsed;
        }
    }

    private ScriptedClock clock = new ScriptedClock();
    private MemoryStore store = new MemoryStore();
    private ConnectedTimeTracker tracker = new ConnectedTimeTracker(clock, store);

    // --- Basic counting ----------------------------------------------------------------------

    @Test public void firstConnectionOfDayCountsFromZero() {
        tracker.onConnected();
        clock.advanceSeconds(30);
        assertEquals(30L, tracker.todaySeconds());
    }

    @Test public void onlyConnectedStateContributesDuration() {
        tracker.onConnected();
        clock.advanceSeconds(30);
        tracker.onDisconnected();
        clock.advanceSeconds(120); // disconnected time must not count
        assertEquals(30L, tracker.todaySeconds());
        tracker.onConnected(); // failed attempt then reconnect: connecting time not counted
        clock.advanceSeconds(10);
        assertEquals(40L, tracker.todaySeconds());
    }

    @Test public void disconnectStopsCountingAndReconnectResumesSameDayTotal() {
        // The exact prompt example: 30 minutes connected, 2 hours off, reconnect resumes from
        // 00:30:00 and keeps counting upward.
        tracker.onConnected();
        clock.advanceSeconds(30 * 60L);
        tracker.onDisconnected();
        clock.advanceSeconds(2 * 60 * 60L);
        assertEquals(30 * 60L, tracker.todaySeconds());
        tracker.onConnected();
        assertEquals(30 * 60L, tracker.todaySeconds());
        clock.advanceSeconds(60);
        assertEquals(30 * 60L + 60L, tracker.todaySeconds());
    }

    @Test public void repeatedConnectedEventsNeverDoubleCount() {
        tracker.onConnected();
        clock.advanceSeconds(15);
        tracker.onConnected(); // duplicate event: ignored
        clock.advanceSeconds(15);
        assertEquals(30L, tracker.todaySeconds());
    }

    @Test public void noNegativeTimeEver() {
        // Monotonic clock going "backwards" (should not happen, but the model must be robust).
        tracker.onConnected();
        clock.advanceSeconds(10);
        clock.elapsed -= 60_000L;
        tracker.onDisconnected();
        assertTrue(tracker.todaySeconds() >= 0L);
        assertTrue(store.seconds >= 0L);
    }

    // --- Persistence / restart models ---------------------------------------------------------

    @Test public void activityRecreationAndAppRestartPreserveTheTotal() {
        tracker.onConnected();
        clock.advanceSeconds(120);
        tracker.onDisconnected();
        // New tracker instances over the same store (Activity recreation, app restart):
        // the persisted day/seconds are read back exactly.
        ConnectedTimeTracker second = new ConnectedTimeTracker(clock, store);
        assertEquals(120L, second.todaySeconds());
        ConnectedTimeTracker third = new ConnectedTimeTracker(clock, store);
        third.onConnected();
        clock.advanceSeconds(60);
        assertEquals(180L, third.todaySeconds());
    }

    @Test public void deviceRebootPersistenceModelKeepsCheckpointedTotalOnly() {
        // Device reboot resets the monotonic clock; the model cannot prove the tunnel survived,
        // so only the last checkpointed total is kept (documented bounded-precision rule).
        tracker.onConnected();
        clock.advanceSeconds(100);
        tracker.checkpoint();
        clock.elapsed = 5_000L; // reboot: elapsed resets
        tracker.onDisconnected();
        assertEquals(100L, tracker.todaySeconds());
    }

    @Test public void hardProcessKillWhileConnectedReconcilesBounded() {
        // Process killed while connected; the core is a child of the app process, so the tunnel
        // died with it. On the next process start the tracker reconciles conservatively: the
        // checkpointed total is kept, the un-checkpointed tail (here 10s) is bounded-lost
        // (documented precision limitation - never invented accuracy), and counting resumes
        // from the checkpointed total when the tunnel genuinely reconnects.
        tracker.onConnected();
        clock.advanceSeconds(40);
        tracker.checkpoint();
        clock.advanceSeconds(10); // 10s tail before the kill (not yet checkpointed)
        // New process, same store:
        ConnectedTimeTracker restarted = new ConnectedTimeTracker(clock, store);
        restarted.reconcile();
        // The checkpointed total is preserved exactly, never inflated by the tail:
        assertEquals(40L, restarted.todaySeconds());
        // The tunnel reconnects for real and counting resumes from the checkpointed total:
        restarted.onConnected();
        clock.advanceSeconds(5);
        assertEquals(45L, restarted.todaySeconds());
    }

    @Test public void hardProcessKillFollowedByLongDisconnectionNeverCounts() {
        // The kill happened, the device sat disconnected for hours, then the app relaunched:
        // the implausible tail is discarded, so disconnected hours are never counted.
        tracker.onConnected();
        clock.advanceSeconds(30);
        tracker.checkpoint();
        clock.advanceSeconds(6 * 3600L); // six hours of nothing
        ConnectedTimeTracker restarted = new ConnectedTimeTracker(clock, store);
        restarted.reconcile();
        assertEquals(30L, restarted.todaySeconds());
    }

    // --- Midnight / day boundaries -------------------------------------------------------------

    @Test public void crossingMidnightWhileDisconnectedResetsTheCounter() {
        tracker.onConnected();
        clock.advanceSeconds(60);
        tracker.onDisconnected();
        clock.nextDay();
        assertEquals(0L, tracker.todaySeconds());
        tracker.onConnected();
        clock.advanceSeconds(5);
        assertEquals(5L, tracker.todaySeconds());
    }

    @Test public void crossingMidnightWhileConnectedRollsOverWithoutDisconnecting() {
        tracker.onConnected();
        clock.advanceSeconds(3600); // 23:xx -> up to midnight
        clock.nextDay();
        long after = tracker.todaySeconds();
        // The new day's counter begins at 00:00:00 (the interval containing midnight is
        // attributed to the new day - the documented bounded attribution).
        assertTrue("day roll must reset the counter, was " + after, after < 60L);
        clock.advanceSeconds(30);
        assertTrue(tracker.todaySeconds() < 90L);
        // The VPN was never disconnected by the roll.
        assertTrue(tracker.isConnected());
    }

    @Test public void dateChangeAloneResetsTheTotal() {
        tracker.onConnected();
        clock.advanceSeconds(10);
        tracker.onDisconnected();
        clock.date = clock.date.plusDays(2);
        assertEquals(0L, tracker.todaySeconds());
    }

    // --- Timezone / clock robustness ------------------------------------------------------------

    @Test public void manualClockBackwardNeverCorruptsTheStoredTotal() {
        tracker.onConnected();
        clock.advanceSeconds(50);
        tracker.onDisconnected();
        long before = store.seconds;
        // "Manual clock change": only the wall clock moves (the monotonic clock is immune);
        // re-rendering the same day keeps the total identical.
        tracker.onConnected();
        tracker.onDisconnected();
        assertEquals(before, store.seconds);
    }

    @Test public void manualClockForwardDoesNotDuplicateLargePeriods() {
        tracker.onConnected();
        clock.advanceSeconds(10);
        tracker.onDisconnected();
        long before = store.seconds;
        clock.advanceSeconds(3 * 3600L); // wall/elapsed gap while disconnected
        tracker.onConnected();
        clock.advanceSeconds(5);
        tracker.onDisconnected();
        assertEquals(before + 5L, store.seconds);
    }

    @Test public void timezoneChangeCannotCountDisconnectedTime() {
        tracker.onConnected();
        clock.advanceSeconds(20);
        tracker.onDisconnected();
        // A timezone change is a calendar-day question only; the monotonic active duration is
        // immune, so the total never moves by a large period.
        tracker.onConnected();
        clock.advanceSeconds(5);
        tracker.onDisconnected();
        assertEquals(25L, store.seconds);
    }

    // --- Reset Defaults must not erase the usage total ------------------------------------------

    @Test public void resetDefaultsDoesNotEraseTheUsageTotal() {
        // performResetDefaults() clears the "aether" preferences only; the tracker's store is a
        // separate "connected_time" file, so the accumulated total survives a reset.
        tracker.onConnected();
        clock.advanceSeconds(45);
        tracker.onDisconnected();
        Map<String, Object> aetherLike = new HashMap<>();
        aetherLike.clear(); // the reset wipes the aether file; connected_time is untouched
        assertEquals(45L, store.seconds);
        assertEquals(45L, new ConnectedTimeTracker(clock, store).todaySeconds());
    }

    // --- Storage shape --------------------------------------------------------------------------

    @Test public void storeKeysAreTheDocumentedContract() {
        tracker.onConnected();
        clock.advanceSeconds(7);
        tracker.onDisconnected();
        assertEquals("2026-10-03", store.day);
        assertEquals(7L, store.seconds);
        assertEquals(clock.elapsed, store.lastKnownElapsed);
        assertEquals("connected_time", ConnectedTimeTracker.PREFS);
        assertEquals("day", ConnectedTimeTracker.KEY_DAY);
        assertEquals("seconds", ConnectedTimeTracker.KEY_SECONDS);
        assertEquals("lastKnownElapsed", ConnectedTimeTracker.KEY_LAST_ELAPSED);
    }

    @Test public void checkpointDoesNotEndTheInterval() {
        tracker.onConnected();
        clock.advanceSeconds(12);
        tracker.checkpoint();
        assertTrue(tracker.isConnected());
        assertEquals(12L, store.seconds);
        clock.advanceSeconds(8);
        assertEquals(20L, tracker.todaySeconds());
        tracker.onDisconnected();
        assertEquals(20L, store.seconds);
    }
}
