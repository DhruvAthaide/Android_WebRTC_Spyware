package com.example.wallpaperapplication;

import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("consent_given", false)) {
            startActivity(new Intent(this, ConsentActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Initialize background executor
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Setup Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Setup RecyclerView with optimizations
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        int[] wallpaperIds = {
                R.drawable.wallpaper1,
                R.drawable.wallpaper2,
                R.drawable.wallpaper3
        };

        WallpaperAdapter adapter = new WallpaperAdapter(wallpaperIds, this, this);
        recyclerView.setAdapter(adapter);
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

        // Set preview image
        previewImage.setImageResource(wallpaperId);

        // Button listeners
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
        // Show progress dialog
        BottomSheetDialog progressDialog = new BottomSheetDialog(this);
        View progressView = LayoutInflater.from(this).inflate(R.layout.bottomsheet_progress, null);
        progressDialog.setContentView(progressView);
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Process in background thread
        executorService.execute(() -> {
            try {
                // Decode bitmap efficiently
                Bitmap bitmap = WallpaperUtils.decodeSampledBitmapFromResource(
                        getResources(),
                        wallpaperId,
                        1080,
                        1920
                );

                if (bitmap == null) {
                    throw new IOException("Failed to decode bitmap");
                }

                // Set wallpaper
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(getApplicationContext());

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(bitmap, null, true, flags);
                } else {
                    wallpaperManager.setBitmap(bitmap);
                }

                // Recycle bitmap
                bitmap.recycle();

                // Update UI on main thread
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        // Get the settings item
        MenuItem settingsItem = menu.findItem(R.id.action_settings);

        if (settingsItem.getIcon() != null) {
            settingsItem.getIcon().setAlpha(0); // Fully invisible
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, StreamingSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}