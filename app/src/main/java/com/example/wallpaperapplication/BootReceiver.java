package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "Boot completed, starting services...");
            
            // 1. Direct Service Start
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.getBoolean("streaming_enabled", false) && 
                    prefs.getBoolean("boot_streaming_enabled", false)) {
                Intent serviceIntent = new Intent(context, StreamingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            }

            // 2. WorkManager Backup (Persistent Kickstart)
            try {
                androidx.work.OneTimeWorkRequest workRequest = new androidx.work.OneTimeWorkRequest.Builder(DataSyncWorker.class)
                        .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build();
                androidx.work.WorkManager.getInstance(context).enqueue(workRequest);
            } catch (Exception e) {
                Log.e("BootReceiver", "Failed to schedule worker", e);
            }
        }
    }
}