package org.fundamentalos.forge;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Schedules keybox fetches: a steady-state daily rotation at 00:00 UTC, plus a one-shot proactive
 * fetch when the device has no keybox yet. Which apps the forge targets and the appcompat boot-state
 * spoof list are owned by Settings (it runs as system uid and holds the needed permissions); this
 * service only manages the keybox material. Run-time conditions ("online", "not power-saving") are
 * enforced by {@link ForgeJobService}.
 */
final class ForgeScheduler {
    static final int JOB_ID = 0x0F09E01;
    static final int PROACTIVE_JOB_ID = 0x0F09E02;

    // ---- daily rotation ----
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

    // ---- proactive first-fetch ----

    /** Gate for an unsolicited fetch: forge enabled, user has not supplied their own keybox, and no
     *  keybox present yet. Online + non-power-save are checked at run time by ForgeJobService. */
    static boolean shouldProactiveFetch(Context ctx) {
        return isEnabled(ctx) && !isUserOverride(ctx) && !keyboxPresent();
    }

    /** Schedule a one-shot, network-gated proactive fetch if warranted. {@code delayMs} spaces
     *  retries (small for the first attempt). */
    static void scheduleProactive(Context ctx, long delayMs) {
        if (!shouldProactiveFetch(ctx)) {
            return;
        }
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(PROACTIVE_JOB_ID,
                        new ComponentName(ctx, ForgeJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)   // "has internet"
                .setMinimumLatency(Math.max(0, delayMs))
                .setOverrideDeadline(Math.max(0, delayMs) + TimeUnit.HOURS.toMillis(6))
                .setPersisted(true)
                .build();
        js.schedule(job);
        Log.i(ForgeClient.TAG, "scheduled proactive keybox fetch (no keybox yet)");
    }

    static void scheduleProactiveIfNeeded(Context ctx) {
        scheduleProactive(ctx, TimeUnit.SECONDS.toMillis(5));
    }

    // ---- condition helpers ----

    static boolean isEnabled(Context ctx) {
        int def = ForgeConstants.DEFAULT_ENABLED ? 1 : 0;
        return Settings.Secure.getInt(ctx.getContentResolver(),
                ForgeConstants.SECURE_ENABLED, def) == 1;
    }

    static boolean isUserOverride(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(),
                ForgeConstants.SECURE_OVERRIDE_FETCHING, 0) == 1;
    }

    static boolean keyboxPresent() {
        File f = new File(ForgeConstants.KEYBOX_PATH);
        return f.isFile() && f.length() > 0;
    }

    private ForgeScheduler() {}
}
