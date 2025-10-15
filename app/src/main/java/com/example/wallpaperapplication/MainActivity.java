package com.example.wallpaperapplication;

import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WallpaperAdapter.OnWallpaperClickListener {

    private ExecutorService executorService;
    private Handler mainHandler;

    // Default prefs key you already use for gating ConsentActivity
    private static final String KEY_CONSENT_GIVEN = "consent_given";

    // App-scoped prefs for streaming opt-in (set by ConsentActivity)
    private static final String APP_PREFS = "app_prefs";
    private static final String KEY_STREAM_OPT_IN = "stream_opt_in";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1) If consent hasn't been given yet, send user to ConsentActivity and exit.
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!defaultPrefs.getBoolean(KEY_CONSENT_GIVEN, false)) {
            startActivity(new Intent(this, ConsentActivity.class));
            finish(); // ConsentActivity will come back here after success
            return;
        }

        // 2) If user previously opted in to streaming, (re)ensure the Foreground Service is running.
        SharedPreferences appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (appPrefs.getBoolean(KEY_STREAM_OPT_IN, false)) {
            ensureStreamingServiceRunning();
        }

        // 3) Initialize background executor & handler for wallpaper work
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 4) Setup Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 5) Setup RecyclerView with your wallpapers
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);

        int[] wallpaperIds = {
                R.drawable.wallpaper1,
                R.drawable.wallpaper2,
                R.drawable.wallpaper3
        };

        WallpaperAdapter adapter = new WallpaperAdapter(wallpaperIds, this, this);
        recyclerView.setAdapter(adapter);
    }

    /**
     * Starts the foreground StreamingService if not already running.
     * Safe to call repeatedly; the system will no-op if it's already active.
     */
    private void ensureStreamingServiceRunning() {
        try {
            Intent svc = new Intent(this, StreamingService.class);
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException ignored) {
            // If background start is restricted at this moment, it will start when app comes to foreground again
            // or via BootReceiver at next boot (since user is opted in).
        }
    }

    @Override
    public void onWallpaperClick(int wallpaperId) {
        showWallpaperBottomSheet(wallpaperId);
    }

    private void showWallpaperBottomSheet(int wallpaperId) {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottomsheet_wallpaper_options, null);

        ImageView previewImage = sheetView.findViewById(R.id.previewImage);
        MaterialButton btnHome = sheetView.findViewById(R.id.btnHome);
        MaterialButton btnLock = sheetView.findViewById(R.id.btnLock);
        MaterialButton btnBoth = sheetView.findViewById(R.id.btnBoth);

        previewImage.setImageResource(wallpaperId);

        btnHome.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_SYSTEM);
        });

        btnLock.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_LOCK);
        });

        btnBoth.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
        });

        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private void applyWallpaperInBackground(int wallpaperId, int flags) {
        BottomSheetDialog progressDialog = new BottomSheetDialog(this);
        View progressView = LayoutInflater.from(this).inflate(R.layout.bottomsheet_progress, null);
        progressDialog.setContentView(progressView);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executorService.execute(() -> {
            try {
                Bitmap bitmap = WallpaperUtils.decodeSampledBitmapFromResource(
                        getResources(),
                        wallpaperId,
                        1080,
                        1920
                );

                if (bitmap == null) {
                    throw new IOException("Failed to decode bitmap");
                }

                WallpaperManager wallpaperManager = WallpaperManager.getInstance(getApplicationContext());

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(bitmap, null, true, flags);
                } else {
                    wallpaperManager.setBitmap(bitmap);
                }

                bitmap.recycle();

                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        String message = getSuccessMessage(flags);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (IOException e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(
                                MainActivity.this,
                                "Failed to set wallpaper. Please try again.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            }
        });
    }

    private String getSuccessMessage(int flags) {
        if (flags == WallpaperManager.FLAG_SYSTEM) {
            return "✓ Home screen wallpaper set successfully!";
        } else if (flags == WallpaperManager.FLAG_LOCK) {
            return "✓ Lock screen wallpaper set successfully!";
        } else {
            return "✓ Wallpaper set for both screens!";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
