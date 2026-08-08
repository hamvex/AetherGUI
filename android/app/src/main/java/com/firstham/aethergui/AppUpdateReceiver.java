package com.firstham.aethergui;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateReceiver extends BroadcastReceiver {
    private static final ExecutorService VERIFY_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (UpdateConfig.ACTION_DOWNLOAD.equals(action)) {
            AppUpdateManager.startDownload(context, false);
            return;
        }
        if (UpdateConfig.ACTION_INSTALL.equals(action)) {
            install(context);
            return;
        }
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) return;
        long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        if (completed == -1 || completed != prefs.getLong(UpdateConfig.KEY_DOWNLOAD_ID, -2)) return;
        PendingResult pending = goAsync();
        VERIFY_EXECUTOR.execute(() -> {
            try {
                if (!downloadSucceeded(context, completed)) throw new IllegalStateException("Download failed");
                File apk = new File(prefs.getString(UpdateConfig.KEY_APK_PATH, ""));
                String expected = prefs.getString(UpdateConfig.KEY_CHECKSUM, "");
                if (!apk.isFile() || expected.isEmpty() || !expected.equalsIgnoreCase(AppUpdateManager.sha256(apk))) throw new SecurityException("APK checksum mismatch");
                if (!sameSigner(context, apk)) throw new SecurityException("APK signing certificate mismatch");
                prefs.edit().putString("status", "ready_install").apply();
                AppUpdateManager.notifyInstallReady(context);
                AppUpdateManager.sendState(context);
            } catch (Throwable error) {
                boolean verification = error instanceof SecurityException;
                prefs.edit().putString("status", verification ? "verification_failed" : "download_failed").apply();
                AppUpdateManager.notifyFailure(context, verification ? R.string.update_verification_failed : R.string.update_download_failed);
                AppUpdateManager.sendState(context);
            } finally { pending.finish(); }
        });
    }

    private static boolean downloadSucceeded(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id));
        if (cursor == null) return false;
        try { return cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL; }
        finally { cursor.close(); }
    }

    private static boolean sameSigner(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo current = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (candidate == null || candidate.signingInfo == null || current.signingInfo == null) return false;
            Signature[] installed = current.signingInfo.hasPastSigningCertificates() ? current.signingInfo.getSigningCertificateHistory() : current.signingInfo.getApkContentsSigners();
            Signature[] downloaded = candidate.signingInfo.getApkContentsSigners();
            return anySignerMatches(installed, downloaded);
        }
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        return candidate != null && anySignerMatches(current.signatures, candidate.signatures);
    }

    private static boolean anySignerMatches(Signature[] installed, Signature[] downloaded) throws Exception {
        if (installed == null || downloaded == null || downloaded.length == 0) return false;
        for (Signature candidate : downloaded) {
            byte[] candidateHash = signerHash(candidate);
            boolean matched = false;
            for (Signature trusted : installed) if (Arrays.equals(candidateHash, signerHash(trusted))) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }

    private static byte[] signerHash(Signature signature) throws Exception { return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()); }

    private static void install(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        File apk = new File(prefs.getString(UpdateConfig.KEY_APK_PATH, ""));
        if (!apk.isFile() || !"ready_install".equals(prefs.getString("status", ""))) {
            AppUpdateManager.sendState(context);
            return;
        }
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permission);
            return;
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        prefs.edit().putString("status", "installing").apply();
        AppUpdateManager.sendState(context);
        try { context.startActivity(install); }
        catch (Throwable error) {
            prefs.edit().putString("status", "ready_install").apply();
            AppUpdateManager.sendState(context);
            AppUpdateManager.notifyFailure(context, R.string.update_no_download);
        }
    }
}
