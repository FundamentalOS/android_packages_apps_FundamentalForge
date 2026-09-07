package com.fundamentalos.forge;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** Runs one fetch at 00:00 UTC, then re-arms for the next day. */
public class ForgeJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            String r = ForgeClient.fetchAndRotate();
            Log.i(ForgeClient.TAG, "JOB RESULT " + r);
            ForgeScheduler.scheduleDaily(getApplicationContext());
            jobFinished(params, false);
        }, "forge-job").start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if interrupted
    }
}
