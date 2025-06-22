package com.example.wallpaperapplication;

import android.Manifest;
import android.content.DialogInterface;
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
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

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
        // if any permission is missing, request them
        boolean missing = false;
        for (String perm: REQUIRED_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(this, perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }

        if (missing) {
            ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
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
