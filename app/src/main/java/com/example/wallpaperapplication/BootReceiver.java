package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean shouldStartService = prefs.getBoolean("start_service", false);
            if (shouldStartService) {
                Intent serviceIntent = new Intent(context, StreamingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}