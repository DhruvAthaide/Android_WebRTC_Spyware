package com.example.wallpaperapplication;

import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
// ✅ Use AndroidX preference manager so you and ConsentActivity share the same prefs
import androidx.preference.PreferenceManager;

import android.view.LayoutInflater;
// import android.view.Menu;      // not used
// import android.view.MenuItem;  // not used
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
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("consent_given", false)) {
            startActivity(new Intent(this, ConsentActivity.class));
            finish(); // OK to finish here; ConsentActivity will relaunch MainActivity after success
            return;
        }

        // Initialize background executor
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Setup Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Setup RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);

        // (Optional) The drawing cache APIs are deprecated/no-ops on modern RecyclerView:
        // recyclerView.setItemViewCacheSize(20);
        // recyclerView.setDrawingCacheEnabled(true);
        // recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

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
