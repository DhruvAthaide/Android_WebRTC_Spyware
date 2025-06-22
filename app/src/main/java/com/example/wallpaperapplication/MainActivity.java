package com.example.wallpaperapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {
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

        // Hook up the Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // RecyclerView setup
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));

        int[] wallpaperIds = {
                R.drawable.wallpaper1,
                R.drawable.wallpaper2,
                R.drawable.wallpaper3
        };

        WallpaperAdapter adapter = new WallpaperAdapter(wallpaperIds, this);
        recyclerView.setAdapter(adapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
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
