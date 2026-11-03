package ru.sitewatch.monitor;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CheckWorker extends Worker {
    private static final String UNIQUE_WORK_NAME = "background_site_monitor_v1";

    public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        int minutes = SiteStore.getInterval(ctx);
        long minAgeMs = minutes * 60_000L;
        long now = System.currentTimeMillis();
        List<SiteStore.Site> sites = SiteStore.all(ctx);
        for (SiteStore.Site site : sites) {
            if (isStopped()) break;
            if (site.checkedAt > 0 && now - site.checkedAt < minAgeMs) continue;
            SiteChecker.checkOne(ctx, site);
        }
        return Result.success();
    }

    public static void schedule(Context ctx) {
        WorkManager manager = WorkManager.getInstance(ctx.getApplicationContext());
        if (SiteStore.all(ctx).isEmpty()) {
            manager.cancelUniqueWork(UNIQUE_WORK_NAME);
            return;
        }
        long backgroundMinutes = Math.max(15L, SiteStore.getInterval(ctx));
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CheckWorker.class, backgroundMinutes, TimeUnit.MINUTES).build();
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request);
    }
}
