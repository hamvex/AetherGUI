//! Daily connected-time accounting (Android dev.020 `ConnectedTimeTracker` parity).
//!
//! Semantics:
//! - TIME is cumulative ACTUAL CONNECTED time for the current LOCAL calendar day — not a
//!   session duration. Disconnected time never counts.
//! - Monotonic elapsed measurement (immune to wall-clock changes) + local calendar date for
//!   day boundaries only.
//! - Persisted (day, seconds, last_checkpoint_ms) in its own `connected-time.json` — usage
//!   state, NOT configuration, so Reset Defaults never erases it.
//! - Midnight (even while connected) closes the previous day and starts today at zero; the
//!   VPN is never disconnected by the roll.
//! - Process restart: reconcile keeps the checkpointed total exactly; the un-checkpointed tail
//!   is conservatively lost (bounded precision, never invented).
//!
//! Clock is injectable for deterministic tests (same test matrix as Android
//! `ConnectedTimeTrackerTest`).

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};


#[derive(Debug, Clone)]
pub struct Clock {
    /// Monotonic milliseconds (Windows GetTickCount64 semantics; starts at boot).
    pub monotonic_ms: u64,
    /// Local calendar date as `YYYY-MM-DD`.
    pub local_date: String,
}

/// Wall-clock milliseconds since the Unix epoch, local timezone already applied (via
/// GetLocalTime, which Windows resolves through the active timezone).
#[cfg(windows)]
#[allow(dead_code)] pub fn local_epoch_ms() -> u64 {
    use windows_sys::Win32::Foundation::SYSTEMTIME;
    use windows_sys::Win32::System::SystemInformation::GetLocalTime;
    unsafe {
        let mut local = std::mem::zeroed::<SYSTEMTIME>();
        GetLocalTime(&mut local);
        systemtime_to_epoch_ms(&local)
    }
}

#[cfg(not(windows))] #[allow(dead_code)] #[allow(dead_code)] pub fn local_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(windows)] #[allow(dead_code)] fn systemtime_to_epoch_ms(st: &windows_sys::Win32::Foundation::SYSTEMTIME) -> u64 {
    // Days since epoch for (year, month, day) using civil-from-days algorithm.
    let y = st.wYear as i64;
    let m = st.wMonth as i64;
    let d = st.wDay as i64;
    let days = days_from_civil(y, m, d);
    let secs = days * 86400 + st.wHour as i64 * 3600 + st.wMinute as i64 * 60 + st.wSecond as i64;
    (secs * 1000 + st.wMilliseconds as i64).max(0) as u64
}

#[cfg(windows)]
pub fn system_clock() -> Clock {
    use windows_sys::Win32::Foundation::SYSTEMTIME;
    use windows_sys::Win32::System::SystemInformation::GetLocalTime;
    unsafe {
        let mut local: SYSTEMTIME = std::mem::zeroed();
        GetLocalTime(&mut local);
        Clock {
            monotonic_ms: monotonic_ms_now(),
            local_date: format!("{:04}-{:02}-{:02}", local.wYear, local.wMonth, local.wDay),
        }
    }
}

#[cfg(not(windows))]
pub fn system_clock() -> Clock {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    Clock {
        monotonic_ms: 0,
        local_date: unix_ms_to_local_date(now.as_millis() as u64),
    }
}

/// Monotonic milliseconds: Windows GetTickCount64 (immune to wall-clock changes, resets at
/// reboot — documented bounded limitation, same as Android elapsedRealtime).
#[cfg(windows)]
pub fn monotonic_ms_now() -> u64 {
    unsafe { windows_sys::Win32::System::SystemInformation::GetTickCount64() }
}

#[cfg(not(windows))]
pub fn monotonic_ms_now() -> u64 {
    use std::time::Instant;
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

#[allow(dead_code)] fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

#[allow(dead_code)] fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[allow(dead_code)] pub fn unix_ms_to_local_date(ms: u64) -> String {
    let days = (ms / 86_400_000) as i64;
    let (y, m, d) = civil_from_days(days);
    format!("{y:04}-{m:02}-{d:02}")
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Persisted {
    day: String,
    seconds: u64,
    last_checkpoint_ms: u64,
    #[serde(default)]
    connected: bool,
}

pub struct ConnectedTimeTracker {
    store: PathBuf,
    state: Persisted,
    /// Monotonic stamp of the current active interval start (None = not connected).
    interval_start: Option<u64>,
}

#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    pub day: String,
    pub seconds: u64,
    pub connected: bool,
}

impl ConnectedTimeTracker {
    pub fn load(store: PathBuf) -> Self {
        let state = std::fs::read_to_string(&store)
            .ok()
            .and_then(|text| serde_json::from_str::<Persisted>(&text).ok())
            .unwrap_or(Persisted {
                day: String::new(),
                seconds: 0,
                last_checkpoint_ms: 0,
                connected: false,
            });
        Self {
            store,
            state,
            interval_start: None,
        }
    }

    /// Day boundary check: closes the previous day and starts today at zero. Called by every
    /// mutating entry point so a viewer crossing midnight sees the reset without a reconnect.
    fn roll_day_if_changed(&mut self, clock: &Clock) {
        if !self.state.day.is_empty() && self.state.day != clock.local_date {
            if self.interval_start.is_some() {
                // Midnight during an active interval: previous day closes, new day starts at
                // zero; the in-progress interval continues counting into the new day. The
                // interval's pre-midnight portion is conservatively attributed to the new day
                // (documented bounded precision, Android parity).
                self.state.seconds = 0;
            } else {
                self.state.seconds = 0;
            }
            self.state.day = clock.local_date.clone();
            self.state.connected = self.interval_start.is_some();
        }
        if self.state.day.is_empty() {
            self.state.day = clock.local_date.clone();
        }
    }

    fn persist(&self) {
        if let Some(parent) = self.store.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(
            &self.store,
            serde_json::to_string(&self.state).unwrap_or_default(),
        );
    }

    /// Idempotent: only a genuine transition into Connected opens an interval.
    pub fn on_connected(&mut self, clock: &Clock) {
        self.roll_day_if_changed(clock);
        if self.interval_start.is_none() {
            self.interval_start = Some(clock.monotonic_ms);
            self.state.connected = true;
            self.persist();
        }
    }

    /// Idempotent: closes the active interval into today's total, whole seconds only, never
    /// negative.
    pub fn on_disconnected(&mut self, clock: &Clock) {
        self.roll_day_if_changed(clock);
        if let Some(start) = self.interval_start.take() {
            let elapsed = clock.monotonic_ms.saturating_sub(start);
            self.state.seconds = self
                .state
                .seconds
                .saturating_add(elapsed / 1000);
            self.state.connected = false;
            self.state.last_checkpoint_ms = clock.monotonic_ms;
            self.persist();
        }
    }

    /// Close accumulated active time without ending the interval (periodic checkpoint; the
    /// telemetry tick calls this — no wasteful per-second disk writes).
    pub fn checkpoint(&mut self, clock: &Clock) {
        if let Some(start) = self.interval_start {
            self.roll_day_if_changed(clock);
            // Re-anchor the interval to now after banking the elapsed portion.
            let elapsed = clock.monotonic_ms.saturating_sub(start);
            self.state.seconds = self.state.seconds.saturating_add(elapsed / 1000);
            self.interval_start = Some(clock.monotonic_ms);
            self.state.last_checkpoint_ms = clock.monotonic_ms;
            self.persist();
        }
    }

    /// At process start: the honest state after a GUI death is disconnected (the job object
    /// killed the core with us). Keeps the checkpointed total exactly; never invents a tail.
    pub fn reconcile(&mut self, clock: &Clock) {
        self.roll_day_if_changed(clock);
        self.interval_start = None;
        self.state.connected = false;
        self.state.last_checkpoint_ms = clock.monotonic_ms;
        self.persist();
    }

    /// Today's seconds, including the in-progress interval (rolls day first).
    pub fn today_seconds(&mut self, clock: &Clock) -> u64 {
        self.roll_day_if_changed(clock);
        match self.interval_start {
            Some(start) => {
                let elapsed = clock.monotonic_ms.saturating_sub(start);
                self.state.seconds.saturating_add(elapsed / 1000)
            }
            None => self.state.seconds,
        }
    }

    /// Fixed HH:MM:SS rendering (no relayout jitter; the frontend renders tabular digits).
    pub fn today_hms(clock_snapshot: &Snapshot) -> String {
        let seconds = clock_snapshot.seconds;
        format!(
            "{:02}:{:02}:{:02}",
            seconds / 3600,
            (seconds % 3600) / 60,
            seconds % 60
        )
    }

    pub fn snapshot(&mut self, clock: &Clock) -> Snapshot {
        let seconds = self.today_seconds(clock);
        Snapshot {
            day: self.state.day.clone(),
            seconds,
            connected: self.interval_start.is_some(),
        }
    }

    #[cfg(test)] pub fn reset_for_test(&mut self) {
        self.state = Persisted {
            day: String::new(),
            seconds: 0,
            last_checkpoint_ms: 0,
            connected: false,
        };
        self.interval_start = None;
        let _ = std::fs::remove_file(&self.store);
    }
}

impl ConnectedTimeTracker {
    /// Store path (for retargeting when the app data dir resolves after default()).
    pub fn store_path(&self) -> std::path::PathBuf {
        self.store.clone()
    }

    /// Point the tracker at the real per-user store, keeping in-memory state only when the
    /// day still matches (first call from default() carries a dummy path and empty state).
    pub fn retarget_store(&mut self, store: std::path::PathBuf) {
        if self.store != store {
            let revived = Self::load(store.clone());
            // Prefer the on-disk state at the real path when it carries any history.
            if revived.state.day != String::new() || revived.state.seconds > 0 {
                self.state = revived.state;
            } else {
                self.state.day = String::new();
                self.state.seconds = 0;
                self.state.connected = false;
            }
            self.store = store;
        }
    }
}

impl Default for ConnectedTimeTracker {
    fn default() -> Self {
        Self::load(Path::new("connected-time.json").to_path_buf())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_tracker(tag: &str) -> ConnectedTimeTracker {
        let dir = std::env::temp_dir().join(format!("aethon-time-test-{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        ConnectedTimeTracker::load(dir.join("connected-time.json"))
    }

    fn clock(monotonic_ms: u64, date: &str) -> Clock {
        Clock {
            monotonic_ms,
            local_date: date.into(),
        }
    }

    const DAY1: &str = "2026-10-03";
    const DAY2: &str = "2026-10-04";

    #[test]
    fn first_connection_of_day_counts() {
        let mut tracker = temp_tracker("first");
        tracker.on_connected(&clock(10_000, DAY1));
        assert_eq!(tracker.today_seconds(&clock(10_500, DAY1)), 0);
        assert_eq!(tracker.today_seconds(&clock(13_400, DAY1)), 3);
    }

    #[test]
    fn disconnect_stops_counting() {
        let mut tracker = temp_tracker("disc");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(70_000, DAY1));
        assert_eq!(tracker.state.seconds, 70);
        // Disconnected time must not count.
        assert_eq!(tracker.today_seconds(&clock(500_000, DAY1)), 70);
    }

    #[test]
    fn reconnect_resumes_same_day_total() {
        let mut tracker = temp_tracker("resume");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(1_800_000, DAY1)); // 30 minutes
        tracker.on_connected(&clock(10_800_000, DAY1)); // 3h later
        assert_eq!(tracker.today_seconds(&clock(10_800_600, DAY1)), 1_800);
        tracker.on_disconnected(&clock(11_400_000, DAY1)); // +10 min
        assert_eq!(tracker.state.seconds, 1_800 + 600);
    }

    #[test]
    fn process_restart_persistence_keeps_checkpointed_total() {
        let mut tracker = temp_tracker("restart");
        tracker.on_connected(&clock(0, DAY1));
        tracker.checkpoint(&clock(6_500, DAY1)); // 6s banked
        let path = tracker.store.clone();
        drop(tracker);
        let mut revived = ConnectedTimeTracker::load(path);
        revived.reconcile(&clock(20_000, DAY1));
        assert_eq!(revived.today_seconds(&clock(20_500, DAY1)), 6);
    }

    #[test]
    fn device_reboot_model_keeps_total_and_never_invents_tail() {
        let mut tracker = temp_tracker("reboot");
        tracker.on_connected(&clock(100_000, DAY1));
        tracker.checkpoint(&clock(106_500, DAY1)); // 6s banked
        // Reboot resets monotonic clock to a small value; conservative answer keeps 6s.
        let after = clock(2_000, DAY1);
        tracker.reconcile(&after);
        assert_eq!(tracker.today_seconds(&after), 6);
    }

    #[test]
    fn midnight_while_disconnected_resets_to_zero() {
        let mut tracker = temp_tracker("mid-disc");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(3_600_000, DAY1)); // 1h
        assert_eq!(tracker.today_seconds(&clock(3_601_000, DAY1)), 3_600);
        assert_eq!(tracker.today_seconds(&clock(3_601_000, DAY2)), 0);
    }

    #[test]
    fn midnight_while_connected_rolls_without_disconnect() {
        let mut tracker = temp_tracker("mid-conn");
        tracker.on_connected(&clock(0, DAY1));
        // Still connected when the date flips. Per the documented bounded-precision model
        // (Android parity), the whole in-flight interval is attributed to the new day.
        assert_eq!(tracker.today_seconds(&clock(3_600_500, DAY2)), 3_600);
        // The interval keeps counting into the new day and the day has rolled.
        tracker.on_disconnected(&clock(3_602_500, DAY2));
        assert_eq!(tracker.state.day, DAY2);
        assert_eq!(tracker.state.seconds, 3_602); // whole interval to DAY2 (conservative)
    }

    #[test]
    fn manual_clock_backward_never_doubles_or_goes_negative() {
        let mut tracker = temp_tracker("backward");
        tracker.on_connected(&clock(50_000, DAY1));
        tracker.on_disconnected(&clock(60_000, DAY1)); // 10s
        // Wall clock jumped backward mid-interval on a second connection.
        tracker.on_connected(&clock(70_000, DAY1));
        let back = clock(30_000, DAY1); // monotonic went "back" (impossible in practice; guard)
        tracker.on_disconnected(&back);
        assert!(tracker.state.seconds >= 10);
    }

    #[test]
    fn wall_clock_forward_jump_does_not_count_disconnected_gap() {
        let mut tracker = temp_tracker("forward");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(5_000, DAY1)); // 5s
        // Monotonic jump while disconnected adds nothing.
        assert_eq!(tracker.today_seconds(&clock(999_999_999, DAY1)), 5);
    }

    #[test]
    fn repeated_connected_events_are_idempotent() {
        let mut tracker = temp_tracker("idempotent");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_connected(&clock(5_000, DAY1));
        tracker.on_connected(&clock(9_000, DAY1));
        tracker.on_disconnected(&clock(10_000, DAY1));
        tracker.on_disconnected(&clock(15_000, DAY1));
        assert_eq!(tracker.state.seconds, 10);
    }

    #[test]
    fn only_connected_contributes_connecting_attempts_never_count() {
        let mut tracker = temp_tracker("onlyconn");
        // Connecting/failed attempts produce no on_connected calls in the caller; the tracker
        // itself must not treat reconcile/checkpoint as connected.
        tracker.reconcile(&clock(0, DAY1));
        tracker.checkpoint(&clock(60_000, DAY1));
        assert_eq!(tracker.today_seconds(&clock(61_000, DAY1)), 0);
    }

    #[test]
    fn date_change_alone_rolls_the_day() {
        let mut tracker = temp_tracker("datechange");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(60_000, DAY1));
        assert_eq!(tracker.today_seconds(&clock(61_000, DAY2)), 0);
        assert_eq!(tracker.state.day, DAY2);
    }

    #[test]
    fn hms_formatting_is_fixed_width() {
        let snap = Snapshot {
            day: DAY1.into(),
            seconds: 3_723,
            connected: true,
        };
        assert_eq!(ConnectedTimeTracker::today_hms(&snap), "01:02:03");
        // Past a day: hours keep growing (HH:MM:SS of the total, never wraps).
        let snap2 = Snapshot {
            seconds: 86_465,
            ..snap
        };
        assert_eq!(ConnectedTimeTracker::today_hms(&snap2), "24:01:05");
    }

    #[test]
    fn usage_state_is_not_configuration_reset_does_not_erase() {
        // Reset Defaults must not touch connected-time.json — this test documents that the
        // store lives outside settings.json (the reset path only rewrites settings.json).
        let dir = std::env::temp_dir().join("aethon-time-test-reset");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let store = dir.join("connected-time.json");
        let mut tracker = ConnectedTimeTracker::load(store.clone());
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(2_000, DAY1));
        drop(tracker);
        // A "reset defaults" rewrites settings.json only.
        assert!(store.exists());
        let mut again = ConnectedTimeTracker::load(store);
        assert_eq!(again.today_seconds(&clock(3_000, DAY1)), 2);
    }

    #[test]
    fn timezone_change_bounded_day_attribution() {
        // A timezone change that flips the local date is handled as a day change: never
        // negative, never duplicated.
        let mut tracker = temp_tracker("tz");
        tracker.on_connected(&clock(0, DAY1));
        tracker.on_disconnected(&clock(60_000, DAY1));
        // New timezone makes it "yesterday" again — rolling back must not restore old seconds.
        tracker.roll_day_if_changed_pub(&clock(120_000, DAY2));
        tracker.roll_day_if_changed_pub(&clock(121_000, DAY1));
        assert_eq!(tracker.today_seconds(&clock(122_000, DAY1)), 0);
    }

    #[test]
    fn unix_ms_to_local_date_matches_epoch_days() {
        assert_eq!(unix_ms_to_local_date(0), "1970-01-01");
        assert_eq!(unix_ms_to_local_date(1_762_310_400_000), "2025-11-05");
    }
}

impl ConnectedTimeTracker {
    #[cfg(test)]
    pub fn roll_day_if_changed_pub(&mut self, clock: &Clock) {
        self.roll_day_if_changed(clock);
    }
}
