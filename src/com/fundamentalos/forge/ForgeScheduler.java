package com.fundamentalos.forge;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/** Schedules the keybox fetch for the next 00:00 UTC (± flex), then re-arms each run. */
final class ForgeScheduler {
    static final int JOB_ID = 0x0F09E01;

    static void scheduleDaily(Context ctx) {
        long now = System.currentTimeMillis();
        long day = TimeUnit.DAYS.toMillis(1);
        long nextMidnightUtc = ((now / day) + 1) * day;   // next 00:00 UTC
        long delay = nextMidnightUtc - now;

        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ForgeJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + TimeUnit.HOURS.toMillis(2))
                .setPersisted(true)
                .build();
        js.schedule(job);
        Log.i(ForgeClient.TAG, "scheduled next fetch in " + (delay / 60000) + " min (00:00 UTC)");
    }

    private ForgeScheduler() {}
}
