package com.example.wallpaperapplication;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class StreamingSettingsActivity extends AppCompatActivity {

    private EditText signalingUrlEt;
    private Button saveUrlBtn;
    private Button defaultUrlBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streaming_settings);

        signalingUrlEt = findViewById(R.id.et_signaling_url);
        saveUrlBtn     = findViewById(R.id.btn_save_signaling_url);
        defaultUrlBtn  = findViewById(R.id.btn_use_default_signaling_url);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String currentUrl = prefs.getString("signaling_url", StreamingService.DEFAULT_SIGNALING_URL);
        signalingUrlEt.setText(currentUrl);

        saveUrlBtn.setOnClickListener(v -> {
            String input = signalingUrlEt.getText() != null ? signalingUrlEt.getText().toString().trim() : "";
            if (!isValidServerUrl(input)) {
                Toast.makeText(this, "Enter a valid URL, e.g. http://192.168.1.10:3000", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("signaling_url", input).apply();
            Toast.makeText(this, "Signaling server saved", Toast.LENGTH_SHORT).show();
            restartStreamingServiceIfRunning();
        });

        defaultUrlBtn.setOnClickListener(v -> {
            signalingUrlEt.setText(StreamingService.DEFAULT_SIGNALING_URL);
            prefs.edit().putString("signaling_url", StreamingService.DEFAULT_SIGNALING_URL).apply();
            Toast.makeText(this, "Reverted to default signaling server", Toast.LENGTH_SHORT).show();
            restartStreamingServiceIfRunning();
        });
    }

    private boolean isValidServerUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        return Patterns.WEB_URL.matcher(url).matches();
    }

    private void restartStreamingServiceIfRunning() {
        // For the autostream branch you likely always want to (re)start it so the new URL applies.
        stopService(StreamingServiceIntents.stop(this));
        startService(StreamingServiceIntents.start(this));
    }
}
