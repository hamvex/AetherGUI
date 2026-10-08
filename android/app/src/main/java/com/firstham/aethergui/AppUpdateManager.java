package com.firstham.aethergui;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

final class AppUpdateManager {
    private static final String CHANNEL_ID = "aether_app_updates";
    private static final int NOTIFICATION_ID = 2901;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    /** Minimum spacing between automatic update checks against the unauthenticated GitHub API. */
    private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);

    interface Listener { void onComplete(); void onError(Throwable error); }

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        createNotificationChannel(app);
        reconcileDownload(app);
        boolean enabled = app.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false);
        setAutomaticChecks(app, enabled);
    }

    static void setAutomaticChecks(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        WorkManager manager = WorkManager.getInstance(app);
        if (!enabled) {
            manager.cancelUniqueWork("aether-update-check");
            manager.cancelUniqueWork("aether-update-startup");
            return;
        }
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(UpdateWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(UpdateWorker.constraints()).build();
        manager.enqueueUniquePeriodicWork("aether-update-check", ExistingPeriodicWorkPolicy.KEEP, periodic);
        manager.enqueueUniqueWork("aether-update-startup", ExistingWorkPolicy.KEEP, new OneTimeWorkRequest.Builder(UpdateWorker.class).setConstraints(UpdateWorker.constraints()).build());
    }

    static void checkNow(Context context, Listener listener) {
        checkNow(context, listener, false);
    }

    /**
     * Every MainActivity creation used to hit the GitHub API, so a rotation or theme switch burned
     * a request from the unauthenticated quota. Automatic checks now honour a minimum interval;
     * only an explicit user action forces one.
     */
    static void checkNow(Context context, Listener listener, boolean force) {
        Context app = context.getApplicationContext();
        if (!force && !checkIntervalElapsed(app)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(listener::onComplete);
            return;
        }
        if (!CHECKING.compareAndSet(false, true)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(listener::onComplete);
            return;
        }
        app.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE).edit().putString("status", "checking").apply();
        sendState(app);
        EXECUTOR.execute(() -> {
            try {
                checkBlocking(app);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(listener::onComplete);
            } catch (Throwable error) {
                markFailed(app);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> listener.onError(error));
            } finally {
                CHECKING.set(false);
            }
        });
    }

    static boolean checkIntervalElapsed(Context context) {
        long last = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                .getLong(UpdateConfig.KEY_LAST_CHECKED_AT, 0L);
        return checkIntervalElapsed(last, System.currentTimeMillis());
    }

    static boolean checkIntervalElapsed(long lastCheckedAt, long now) {
        if (lastCheckedAt <= 0L) return true;
        // A clock that moved backwards must not lock checking out until it catches up.
        if (lastCheckedAt > now) return true;
        return now - lastCheckedAt >= CHECK_INTERVAL_MS;
    }

    static synchronized void checkBlocking(Context context) throws IOException {
        JSONObject release = latestRelease();
        String tag = release.optString("tag_name", "").replaceFirst("^v", "");
        if (tag.isEmpty()) throw new IOException("No published GitHub release is available");
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(UpdateConfig.KEY_LATEST_VERSION, tag)
                .putLong(UpdateConfig.KEY_LAST_CHECKED_AT, System.currentTimeMillis())
                .apply();
        if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
            prefs.edit().putString("status", "up_to_date").remove(UpdateConfig.KEY_DOWNLOAD_URL).remove(UpdateConfig.KEY_CHECKSUM).apply();
            sendState(context);
            return;
        }
        JSONObject apk = findAsset(release.optJSONArray("assets"), String.format(Locale.US, UpdateConfig.RELEASE_ASSET, tag));
        if (apk == null) throw new IOException("The latest release has no Android update package");
        String downloadUrl = apk.optString("browser_download_url", "");
        if (!downloadUrl.startsWith(UpdateConfig.RELEASE_DOWNLOAD_PREFIX)) throw new IOException("The update URL is not an official Aethon release");
        String checksum = apk.optString("digest", "").replaceFirst("^sha256:", "");
        if (checksum.isEmpty()) {
            JSONObject sums = findAsset(release.optJSONArray("assets"), UpdateConfig.CHECKSUM_ASSET);
            if (sums != null) {
                String sumsUrl = sums.optString("browser_download_url", "");
                if (!sumsUrl.startsWith(UpdateConfig.RELEASE_DOWNLOAD_PREFIX)) throw new IOException("The checksum URL is not an official Aethon release");
                checksum = checksumFromFile(getText(sumsUrl), apk.optString("name"));
            }
        }
        if (checksum.isEmpty()) throw new IOException("The Android APK has no checksum");
        UpdateInfo info = new UpdateInfo(tag, release.optString("body", ""), downloadUrl, checksum);
        prefs.edit().putString(UpdateConfig.KEY_LATEST_VERSION, info.version).putString(UpdateConfig.KEY_RELEASE_NOTES, info.notes)
                .putString(UpdateConfig.KEY_DOWNLOAD_URL, info.downloadUrl).putString(UpdateConfig.KEY_CHECKSUM, info.checksum).apply();
        if (compareVersions(info.version, BuildConfig.VERSION_NAME) > 0) {
            prefs.edit().putString("status", prefs.getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false) ? "downloading" : "available").apply();
            notifyAvailable(context, info.version, info.notes);
            sendState(context);
            if (prefs.getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false)) startDownload(context, false);
        } else {
            prefs.edit().putString("status", "up_to_date").apply();
            sendState(context);
        }
    }

    static void markFailed(Context context) {
        context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE).edit().putString("status", "failed").apply();
        sendState(context);
    }

    static boolean startDownload(Context context, boolean wifiOnly) {
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        String url = prefs.getString(UpdateConfig.KEY_DOWNLOAD_URL, "");
        String version = prefs.getString(UpdateConfig.KEY_LATEST_VERSION, "");
        if (url.isEmpty() || version.isEmpty() || compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return false;
        try {
            DownloadManager manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            long oldId = prefs.getLong(UpdateConfig.KEY_DOWNLOAD_ID, -1);
            if (oldId != -1 && isActive(manager, oldId)) return true;
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IOException("External update storage is unavailable");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create the update directory");
            File apk = new File(dir, String.format(Locale.US, UpdateConfig.RELEASE_ASSET, version));
            if (apk.exists() && !apk.delete()) throw new IOException("Could not replace the previous update");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)).setTitle(app.getString(R.string.new_update_title))
                    .setDescription(app.getString(R.string.update_downloading)).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverRoaming(false).setDestinationUri(Uri.fromFile(apk));
            request.setAllowedNetworkTypes(wifiOnly ? DownloadManager.Request.NETWORK_WIFI : DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            long id = manager.enqueue(request);
            prefs.edit().putLong(UpdateConfig.KEY_DOWNLOAD_ID, id).putString(UpdateConfig.KEY_APK_PATH, apk.getAbsolutePath()).putString("status", "downloading").apply();
            sendState(app);
            return true;
        } catch (Throwable error) {
            prefs.edit().putString("status", "download_failed").apply();
            sendState(app);
            notifyFailure(app, R.string.update_download_failed);
            return false;
        }
    }

    private static void reconcileDownload(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        long id = prefs.getLong(UpdateConfig.KEY_DOWNLOAD_ID, -1);
        if (id < 0) return;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                prefs.edit().remove(UpdateConfig.KEY_DOWNLOAD_ID).putString("status", "download_failed").apply();
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Intent completed = new Intent(context, AppUpdateReceiver.class).setAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                        .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id);
                context.sendBroadcast(completed);
            } else if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED) {
                prefs.edit().putString("status", "downloading").apply();
            } else {
                prefs.edit().remove(UpdateConfig.KEY_DOWNLOAD_ID).putString("status", "download_failed").apply();
            }
        } catch (RuntimeException error) {
            prefs.edit().putString("status", "download_failed").apply();
        }
    }

    @SuppressLint("MissingPermission")
    static void notifyAvailable(Context context, String version, String notes) {
        createNotificationChannel(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        if (version.equals(prefs.getString(UpdateConfig.KEY_NOTIFIED_VERSION, ""))) return;
        if (!notificationsAllowed(context)) return;
        Intent action = new Intent(context, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_DOWNLOAD);
        PendingIntent pending = PendingIntent.getBroadcast(context, 2902, action, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = context.getString(R.string.new_update_versions, BuildConfig.VERSION_NAME, version) + "\n" + (TextUtils.isEmpty(notes) ? context.getString(R.string.no_release_notes) : notes.trim());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_aethon_mono)
                .setContentTitle(context.getString(R.string.new_update_title)).setContentText(context.getString(R.string.new_update_versions, BuildConfig.VERSION_NAME, version))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setOnlyAlertOnce(true).setAutoCancel(true).addAction(0, context.getString(R.string.start_update), pending);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        prefs.edit().putString(UpdateConfig.KEY_NOTIFIED_VERSION, version).apply();
    }

    @SuppressLint("MissingPermission")
    static void notifyInstallReady(Context context) {
        createNotificationChannel(context);
        if (!notificationsAllowed(context)) return;
        Intent action = new Intent(context, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_INSTALL);
        PendingIntent pending = PendingIntent.getBroadcast(context, 2903, action, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_aethon_mono)
                .setContentTitle(context.getString(R.string.new_update_title)).setContentText(context.getString(R.string.update_ready_install))
                .setAutoCancel(true).setOnlyAlertOnce(true).addAction(0, context.getString(R.string.install_update), pending);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    @SuppressLint("MissingPermission")
    static void notifyFailure(Context context, int messageId) {
        createNotificationChannel(context);
        if (!notificationsAllowed(context)) return;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_aethon_mono)
                .setContentTitle(context.getString(R.string.app_updates)).setContentText(context.getString(messageId)).setAutoCancel(true).setOnlyAlertOnce(true);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    static void sendState(Context context) {
        Intent intent = new Intent(UpdateConfig.ACTION_STATE).setPackage(context.getPackageName());
        context.sendBroadcast(intent, AetherVpnService.INTERNAL_PERMISSION);
    }

    static int compareVersions(String left, String right) {
        String[] leftVersion = left.replaceFirst("^v", "").split("\\+", 2)[0].split("-", 2);
        String[] rightVersion = right.replaceFirst("^v", "").split("\\+", 2)[0].split("-", 2);
        String[] a = leftVersion[0].split("\\.");
        String[] b = rightVersion[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? number(a[i]) : 0;
            int bv = i < b.length ? number(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        if (leftVersion.length != rightVersion.length) return leftVersion.length == 1 ? 1 : -1;
        if (leftVersion.length == 1) return 0;
        a = leftVersion[1].split("\\."); b = rightVersion[1].split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean an = a[i].matches("[0-9]+"), bn = b[i].matches("[0-9]+");
            int comparison = an && bn ? new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]))
                    : an != bn ? (an ? -1 : 1) : a[i].compareTo(b[i]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }

    static String checksumFromFile(String content, String assetName) {
        if (content == null) return "";
        for (String line : content.split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[1].trim().equals(assetName)) return parts[0].trim().toLowerCase(Locale.US);
        }
        return "";
    }

    private static int number(String value) { try { return Integer.parseInt(value.replaceAll("[^0-9].*", "")); } catch (Exception ignored) { return 0; } }
    private static JSONObject findAsset(JSONArray assets, String name) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) { JSONObject item = assets.optJSONObject(i); if (item != null && name.equals(item.optString("name"))) return item; }
        return null;
    }
    /**
     * GitHub's latest-release endpoint already resolves the newest non-draft, non-prerelease entry
     * in a single request. The paged listing is kept only as a fallback for the case where that
     * endpoint is unavailable.
     */
    private static JSONObject latestRelease() throws IOException {
        try {
            JSONObject release = getJson(UpdateConfig.LATEST_API_URL);
            if (!release.optBoolean("draft") && !release.optBoolean("prerelease")
                    && !release.optString("tag_name", "").isEmpty()) {
                return release;
            }
        } catch (IOException ignored) { }
        JSONArray releases = getJsonArray(UpdateConfig.API_URL);
        JSONObject best = null;
        String bestTag = "";
        for (int i = 0; i < releases.length(); i++) {
            JSONObject candidate = releases.optJSONObject(i);
            if (candidate == null || candidate.optBoolean("draft") || candidate.optBoolean("prerelease")) continue;
            String candidateTag = candidate.optString("tag_name", "").replaceFirst("^v", "");
            if (candidateTag.isEmpty()) continue;
            if (best == null || compareVersions(candidateTag, bestTag) > 0) { best = candidate; bestTag = candidateTag; }
        }
        if (best == null) throw new IOException("No published GitHub release is available");
        return best;
    }

    private static JSONObject getJson(String url) throws IOException {
        try { return new JSONObject(getText(url)); }
        catch (org.json.JSONException error) { throw new IOException("Invalid update metadata", error); }
    }

    private static JSONArray getJsonArray(String url) throws IOException {
        try { return new JSONArray(getText(url)); }
        catch (org.json.JSONException error) { throw new IOException("Invalid update metadata", error); }
    }
    /**
     * Open {@code url} only if it resolves to HTTPS.
     *
     * <p>Every URL that reaches here today is either a compile-time constant or has
     * been prefix-checked against {@link UpdateConfig#RELEASE_DOWNLOAD_PREFIX}, so this
     * is a backstop rather than the primary control. It matters because it is what
     * guarantees the connection is an {@link HttpsURLConnection} — which verifies the
     * peer's hostname by default — so no future caller can fetch update metadata or a
     * checksum list over a channel someone on the path could rewrite.
     */
    static HttpURLConnection openHttpsOnly(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        if (!(connection instanceof HttpsURLConnection)) throw new IOException("Refusing a non-HTTPS update URL");
        return (HttpURLConnection) connection;
    }

    private static String getText(String url) throws IOException {
        HttpURLConnection connection = openHttpsOnly(url);
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000); connection.setRequestProperty("User-Agent", "Aethon-Android"); connection.setRequestProperty("Accept", "application/vnd.github+json");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder(); String line; while ((line = reader.readLine()) != null) result.append(line).append('\n'); return result.toString();
        } finally { connection.disconnect(); }
    }
    private static boolean isActive(DownloadManager manager, long id) { try { android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id)); if (cursor == null) return false; try { if (!cursor.moveToFirst()) return false; int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED; } finally { cursor.close(); } } catch (Exception ignored) { return false; } }
    static int downloadProgress(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        long id = prefs.getLong(UpdateConfig.KEY_DOWNLOAD_ID, -1);
        if (id < 0) return -1;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return -1;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 100;
            if (status != DownloadManager.STATUS_PENDING && status != DownloadManager.STATUS_RUNNING && status != DownloadManager.STATUS_PAUSED) return -1;
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            return total > 0 ? (int) Math.min(99, downloaded * 100 / total) : 0;
        } catch (Exception ignored) { return -1; }
    }
    private static boolean notificationsAllowed(Context context) { return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    private static void createNotificationChannel(Context context) { NotificationChannel channel = new NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT); channel.setDescription(context.getString(R.string.update_notification_channel_summary)); context.getSystemService(NotificationManager.class).createNotificationChannel(channel); }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32 * 1024]; int count;
        try (FileInputStream input = new FileInputStream(file)) { while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value)); return result.toString();
    }

    private AppUpdateManager() { }
}
