package com.fundamentalos.forge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Entry point for a keybox fetch. Triggerable for testing via:
 *   adb shell am broadcast -a com.fundamentalos.forge.FETCH \
 *       -n com.fundamentalos.forge/.ForgeReceiver
 * and on BOOT_COMPLETED (which schedules the daily 00:00 UTC job).
 */
public class ForgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i(ForgeClient.TAG, "ForgeReceiver: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ForgeScheduler.scheduleDaily(ctx);
            return;
        }
        // FETCH (manual/test): run off the main thread
        final android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        final android.os.PowerManager.WakeLock wl =
                pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "forge:fetch");
        wl.acquire(60_000);
        new Thread(() -> {
            String r = ForgeClient.fetchAndRotate();
            Log.i(ForgeClient.TAG, "RESULT " + r);
            try { wl.release(); } catch (Exception ignored) {}
        }, "forge-fetch").start();
    }
}
