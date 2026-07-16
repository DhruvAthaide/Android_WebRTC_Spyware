package com.example.wallpaperapplication;

import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * MyFirebaseService receives FCM downstream messages to allow on-demand
 * start/stop of the StreamingService without requiring the service to always
 * be running. This replaces the always-on WorkManager polling pattern.
 *
 * Supported commands (sent via FCM data payload with key "command"):
 *   - "start_stream" → starts StreamingService as a foreground service
 *   - "stop_stream"  → stops StreamingService
 *
 * The FCM token is saved to SharedPreferences and should be relayed to the
 * server so it can send push commands.
 */
public class MyFirebaseService extends FirebaseMessagingService {

    private static final String TAG = "FCM";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "New FCM token: " + token);

        // Persist locally for reference
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString("fcm_token", token).apply();

        // Upload token to signaling server
        sendTokenToServer(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        Log.d(TAG, "FCM message received: " + message.getData());

        if (!message.getData().containsKey("command")) return;
        String command = message.getData().get("command");

        if ("start_stream".equals(command)) {
            Log.d(TAG, "FCM: start_stream");
            // startForegroundService required for Android 8+ background restrictions
            Intent intent = new Intent(this, StreamingService.class);
            ContextCompat.startForegroundService(this, intent);

        } else if ("stop_stream".equals(command)) {
            Log.d(TAG, "FCM: stop_stream");
            Intent intent = new Intent(this, StreamingService.class);
            intent.setAction(Constants.ACTION_STOP_STREAMING);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private static final okhttp3.OkHttpClient HTTP_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    /**
     * Sends the FCM registration token to the server's /api/fcm-token endpoint.
     * Runs on a background thread to avoid blocking.
     */
    private void sendTokenToServer(String token) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = prefs.getString(Constants.PREF_SIGNALING_URL, Constants.DEFAULT_SIGNALING_URL);

        new Thread(() -> {
            try {
                android.net.Uri uri = android.net.Uri.parse(serverUrl).buildUpon()
                        .appendPath("api")
                        .appendPath("fcm-token")
                        .build();

                String deviceId = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

                org.json.JSONObject body = new org.json.JSONObject();
                body.put("token", token);
                body.put("deviceId", deviceId);

                okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                        body.toString(),
                        okhttp3.MediaType.parse("application/json")
                );
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(uri.toString())
                        .post(requestBody)
                        .build();

                try (okhttp3.Response response = HTTP_CLIENT.newCall(request).execute()) {
                    Log.d(TAG, "Token sent to server: " + response.code());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send FCM token to server: " + e.getMessage());
            }
        }).start();
    }
}
