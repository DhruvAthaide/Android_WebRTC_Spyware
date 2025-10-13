package com.example.wallpaperapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConsentActivity extends AppCompatActivity {

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new RequestMultiplePermissions(), this::onPermsResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] needed = requiredPermissions();
        if (hasAll(needed)) {
            startStreamingAndFinish();
        } else {
            permLauncher.launch(needed);
        }
    }

    private String[] requiredPermissions() {
        List<String> req = new ArrayList<>();
        req.add(Manifest.permission.CAMERA);
        req.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            // Foreground service must post a notification within 5s
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return req.toArray(new String[0]);
    }

    private boolean hasAll(@NonNull String[] perms) {
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void onPermsResult(Map<String, Boolean> result) {
        String[] needed = requiredPermissions();
        if (hasAll(needed)) {
            startStreamingAndFinish();
        } else {
            Toast.makeText(this, "Permissions required to start streaming.", Toast.LENGTH_LONG).show();
            finish(); // Don’t loop; user can relaunch app to retry
        }
    }

    private void startStreamingAndFinish() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean("consent_given", true)
                .putBoolean("start_service", true)
                .apply();


        Intent svc = new Intent(this, StreamingService.class);
        ContextCompat.startForegroundService(this, svc);

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
