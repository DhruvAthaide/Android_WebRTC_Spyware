package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        boolean userOptIn = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("stream_opt_in", false);
        if (!userOptIn) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Intent svc = new Intent(context, StreamingService.class);
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, svc);
            } catch (IllegalStateException e) {
                android.util.Log.w("BootReceiver", "FGS start blocked by background restrictions", e);
            }
        }
    }
}
