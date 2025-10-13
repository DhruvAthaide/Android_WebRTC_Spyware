package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean shouldStart = prefs.getBoolean("start_service", false);
        if (!shouldStart) return;

        // If on Android 13+ and notifications disabled, DO NOT start (service would be killed)
        if (Build.VERSION.SDK_INT >= 33) {
            boolean enabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
            if (!enabled) return;
        }

        String act = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(act)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(act)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) {
            Intent svc = new Intent(context, StreamingService.class);
            ContextCompat.startForegroundService(context, svc);
        }
    }
}
