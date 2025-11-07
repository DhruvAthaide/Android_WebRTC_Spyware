package com.example.wallpaperapplication;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;

public class StreamingSettingsActivity extends AppCompatActivity {
    private Switch streamingSwitch;
    private Switch bootSwitch;
    private Button stopButton;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private BroadcastReceiver permissionErrorReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streaming_settings);

        streamingSwitch = findViewById(R.id.streaming_switch);
        bootSwitch = findViewById(R.id.switch_boot);
        stopButton = findViewById(R.id.btn_stop);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        streamingSwitch.setChecked(prefs.getBoolean("streaming_enabled", false));
        bootSwitch.setChecked(prefs.getBoolean("boot_streaming_enabled", false));

        streamingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (checkPermissions()) {
                    startStreamingService();
                    checkNotificationAccess();
                    prefs.edit().putBoolean("streaming_enabled", true).apply();
                } else {
                    streamingSwitch.setChecked(false);
                }
            } else {
                stopStreamingService();
                prefs.edit().putBoolean("streaming_enabled", false).apply();
            }
        });

        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("boot_streaming_enabled", isChecked).apply();
        });

        stopButton.setOnClickListener(v -> {
            stopStreamingService();
            streamingSwitch.setChecked(false);
            prefs.edit().putBoolean("streaming_enabled", false).apply();
        });

        permissionErrorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                streamingSwitch.setChecked(false);
                Toast.makeText(context, "Streaming failed: Missing permissions", Toast.LENGTH_SHORT).show();
                promptEnablePermissions();
            }
        };
        IntentFilter filter = new IntentFilter("com.example.wallpaperapplication.PERMISSION_ERROR");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(permissionErrorReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(permissionErrorReceiver, filter, null, null, Context.RECEIVER_NOT_EXPORTED);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (permissionErrorReceiver != null) {
            unregisterReceiver(permissionErrorReceiver);
        }
    }

    private boolean checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALL_LOG);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private void checkNotificationAccess() {
        String enabledListeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        String packageName = getPackageName();
        String listenerName = packageName + "/" + StreamingService.NotificationListener.class.getName();
        if (enabledListeners == null || !enabledListeners.contains(listenerName)) {
            Toast.makeText(this, "Please enable notification access for streaming notifications", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        }
    }

    private void promptEnablePermissions() {
        Toast.makeText(this, "Please enable required permissions in settings", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void startStreamingService() {
        Intent intent = new Intent(this, StreamingService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopStreamingService() {
        Intent intent = new Intent(this, StreamingService.class);
        intent.setAction("STOP_STREAMING");
        ContextCompat.startForegroundService(this, intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startStreamingService();
                checkNotificationAccess();
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putBoolean("streaming_enabled", true).apply();
            } else {
                Toast.makeText(this, "Permissions required for streaming, call logs, SMS, and location", Toast.LENGTH_SHORT).show();
                streamingSwitch.setChecked(false);
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ||
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CALL_LOG) ||
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_SMS) ||
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS))) {
                    promptEnablePermissions();
                }
            }
        }
    }
}