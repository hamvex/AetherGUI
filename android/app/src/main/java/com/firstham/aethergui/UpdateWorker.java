package com.firstham.aethergui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

public final class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        try {
            AppUpdateManager.checkBlocking(getApplicationContext());
            return Result.success();
        } catch (IOException error) {
            AppUpdateManager.markFailed(getApplicationContext());
            return Result.retry();
        } catch (Exception error) {
            AppUpdateManager.markFailed(getApplicationContext());
            return Result.failure();
        }
    }

    public static Constraints constraints() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }
}
