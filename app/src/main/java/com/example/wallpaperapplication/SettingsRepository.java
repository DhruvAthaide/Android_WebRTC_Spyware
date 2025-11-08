package com.example.wallpaperapplication;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public final class SettingsRepository {
    private SettingsRepository() {}

    public static String getSignalingUrl(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getString("signaling_url", StreamingService.DEFAULT_SIGNALING_URL);
    }
}
