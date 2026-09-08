package org.fundamentalos.forge;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/** Runs the daily rotation (00:00 UTC) and one-shot proactive fetches. */
public class ForgeJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        final int id = params.getJobId();
        new Thread(() -> {
            if (id == ForgeScheduler.PROACTIVE_JOB_ID) {
                runProactive();
            } else {
                String r = ForgeClient.fetchAndRotate();
                Log.i(ForgeClient.TAG, "JOB " + r);
                ForgeScheduler.scheduleDaily(getApplicationContext());
            }
            jobFinished(params, false);
        }, "forge-job").start();
        return true; // work continues on the thread
    }

    private void runProactive() {
        // Re-check the product's conditions at run time: still no keybox + enabled + user has not
        // supplied their own; online (validated internet) and NOT in power-save mode.
        if (!ForgeScheduler.shouldProactiveFetch(this)) {
            return; // keybox arrived, or the user turned it off / took over — nothing to do
        }
        if (isPowerSave() || !isOnline()) {
            // Conditions not met right now; retry later (JobScheduler also defers during power-save).
            ForgeScheduler.scheduleProactive(this, TimeUnit.MINUTES.toMillis(30));
            return;
        }
        String r = ForgeClient.fetchAndRotate();
        Log.i(ForgeClient.TAG, "PROACTIVE " + r);
        if (!ForgeScheduler.keyboxPresent()) {
            // fetch failed (e.g. transient network/server); back off and retry
            ForgeScheduler.scheduleProactive(this, TimeUnit.HOURS.toMillis(1));
        }
    }

    private boolean isPowerSave() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    private boolean isOnline() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        Network n = cm.getActiveNetwork();
        if (n == null) {
            return false;
        }
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if interrupted
    }
}
