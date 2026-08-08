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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class AppUpdateManager {
    private static final String CHANNEL_ID = "aether_app_updates";
    private static final int NOTIFICATION_ID = 2901;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    interface Listener { void onComplete(); void onError(Throwable error); }

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        createNotificationChannel(app);
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(UpdateWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(UpdateWorker.constraints()).build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("aether-update-check", ExistingPeriodicWorkPolicy.KEEP, periodic);
        WorkManager.getInstance(app).enqueueUniqueWork("aether-update-startup", ExistingWorkPolicy.KEEP, new OneTimeWorkRequest.Builder(UpdateWorker.class).setConstraints(UpdateWorker.constraints()).build());
    }

    static void checkNow(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                checkBlocking(app);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(listener::onComplete);
            } catch (Throwable error) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> listener.onError(error));
            }
        });
    }

    static void checkBlocking(Context context) throws IOException {
        JSONObject release = getJson(UpdateConfig.API_URL);
        String tag = release.optString("tag_name", "").replaceFirst("^v", "");
        if (tag.isEmpty()) throw new IOException("The update service returned no version");
        JSONArray assets = release.optJSONArray("assets");
        JSONObject apk = findAsset(assets, String.format(Locale.US, UpdateConfig.RELEASE_ASSET, tag));
        if (apk == null) throw new IOException("The latest release has no Android APK");
        String downloadUrl = apk.optString("browser_download_url", "");
        if (!downloadUrl.startsWith(UpdateConfig.RELEASE_DOWNLOAD_PREFIX)) throw new IOException("The update URL is not an official Aethon release");
        String checksum = apk.optString("digest", "").replaceFirst("^sha256:", "");
        if (checksum.isEmpty()) {
            JSONObject sums = findAsset(assets, UpdateConfig.CHECKSUM_ASSET);
            if (sums != null) {
                String sumsUrl = sums.optString("browser_download_url", "");
                if (!sumsUrl.startsWith(UpdateConfig.RELEASE_DOWNLOAD_PREFIX)) throw new IOException("The checksum URL is not an official Aethon release");
                checksum = checksumFromFile(getText(sumsUrl), apk.optString("name"));
            }
        }
        if (checksum.isEmpty()) throw new IOException("The Android APK has no checksum");
        UpdateInfo info = new UpdateInfo(tag, release.optString("body", ""), downloadUrl, checksum);
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(UpdateConfig.KEY_LATEST_VERSION, info.version).putString(UpdateConfig.KEY_RELEASE_NOTES, info.notes)
                .putString(UpdateConfig.KEY_DOWNLOAD_URL, info.downloadUrl).putString(UpdateConfig.KEY_CHECKSUM, info.checksum).apply();
        if (compareVersions(info.version, BuildConfig.VERSION_NAME) > 0) {
            prefs.edit().putString("status", prefs.getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false) ? "downloading" : "available").apply();
            notifyAvailable(context, info.version, info.notes);
            if (prefs.getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false)) startDownload(context, true);
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
        String[] a = left.replaceFirst("^v", "").split("\\.");
        String[] b = right.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? number(a[i]) : 0;
            int bv = i < b.length ? number(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
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
    private static JSONObject getJson(String url) throws IOException {
        try { return new JSONObject(getText(url)); }
        catch (org.json.JSONException error) { throw new IOException("Invalid update metadata", error); }
    }
    private static String getText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000); connection.setRequestProperty("User-Agent", "Aethon-Android"); connection.setRequestProperty("Accept", "application/vnd.github+json");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder(); String line; while ((line = reader.readLine()) != null) result.append(line).append('\n'); return result.toString();
        } finally { connection.disconnect(); }
    }
    private static boolean isActive(DownloadManager manager, long id) { try { android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id)); if (cursor == null) return false; try { return cursor.moveToFirst() && (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PENDING || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_RUNNING); } finally { cursor.close(); } } catch (Exception ignored) { return false; } }
    private static boolean notificationsAllowed(Context context) { return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    private static void createNotificationChannel(Context context) { NotificationChannel channel = new NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT); channel.setDescription(context.getString(R.string.update_notification_channel_summary)); context.getSystemService(NotificationManager.class).createNotificationChannel(channel); }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32 * 1024]; int count;
        try (FileInputStream input = new FileInputStream(file)) { while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value)); return result.toString();
    }

    private AppUpdateManager() { }
}
