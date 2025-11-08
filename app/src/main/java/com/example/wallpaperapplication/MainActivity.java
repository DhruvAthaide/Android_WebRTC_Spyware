package com.example.wallpaperapplication;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WallpaperAdapter.OnWallpaperClickListener {

    private ExecutorService executorService;
    private Handler mainHandler;

    private static final String KEY_CONSENT_GIVEN = "consent_given";
    private static final String APP_PREFS = "app_prefs";
    private static final String KEY_STREAM_OPT_IN = "stream_opt_in";

    // Launch ConsentActivity for result (don’t finish MainActivity)
    private final ActivityResultLauncher<Intent> consentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // If the user opted-in, start service
                    SharedPreferences appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
                    if (appPrefs.getBoolean(KEY_STREAM_OPT_IN, false)) {
                        ensureStreamingServiceRunning();
                    }
                    // Now initialize the UI (if not already)
                    initUi();
                } else {
                    // User declined permissions/consent -> close or show a minimal screen
                    Toast.makeText(this, "Permissions required to proceed.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean consentGiven = defaultPrefs.getBoolean(KEY_CONSENT_GIVEN, false);
        if (!consentGiven) {
            consentLauncher.launch(new Intent(this, ConsentActivity.class));
            return; // IMPORTANT: don’t finish; wait for result, then initUi()
        }

        // If consent is already given, initialize immediately
        initUi();

        // Start the FGS if opted-in (safe to call repeatedly)
        SharedPreferences appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (appPrefs.getBoolean(KEY_STREAM_OPT_IN, true)) { // default true if you want autostream
            ensureStreamingServiceRunning();
        }
    }

    private void initUi() {
        if (executorService == null) {
            executorService = Executors.newSingleThreadExecutor();
        }
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setHasFixedSize(true);
        int[] wallpaperIds = { R.drawable.wallpaper1, R.drawable.wallpaper2, R.drawable.wallpaper3 };
        recyclerView.setAdapter(new WallpaperAdapter(wallpaperIds, this, this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        // Invisible but tappable “Settings” hotspot
        MenuItem settings = menu.findItem(R.id.action_settings);
        settings.setIcon(null);
        settings.setTitle("");
        settings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        View hotspot = new View(this);
        int sizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        hotspot.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        hotspot.setAlpha(0f);
        hotspot.setClickable(true);
        hotspot.setOnClickListener(v ->
                startActivity(new Intent(this, StreamingSettingsActivity.class)));
        settings.setActionView(hotspot);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, StreamingSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Starts the foreground StreamingService (idempotent). */
    private void ensureStreamingServiceRunning() {
        try {
            Intent svc = new Intent(this, StreamingService.class);
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException ignored) {
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
                        getResources(), wallpaperId, 1080, 1920);

                if (bitmap == null) throw new IOException("Failed to decode bitmap");

                WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    wm.setBitmap(bitmap, null, true, flags);
                } else {
                    wm.setBitmap(bitmap);
                }
                bitmap.recycle();

                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(this, getSuccessMessage(flags), Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (IOException e) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(this, "Failed to set wallpaper. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private String getSuccessMessage(int flags) {
        if (flags == WallpaperManager.FLAG_SYSTEM) return "✓ Home screen wallpaper set successfully!";
        if (flags == WallpaperManager.FLAG_LOCK)   return "✓ Lock screen wallpaper set successfully!";
        return "✓ Wallpaper set for both screens!";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
