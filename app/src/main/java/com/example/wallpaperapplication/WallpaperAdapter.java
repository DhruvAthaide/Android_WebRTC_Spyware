package com.example.wallpaperapplication;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class WallpaperAdapter extends RecyclerView.Adapter<WallpaperAdapter.ViewHolder> {
    private final int[] wallpaperIds;
    private final Context context;
    private final OnWallpaperClickListener listener;

    public interface OnWallpaperClickListener {
        void onWallpaperClick(int wallpaperId);
    }

    public WallpaperAdapter(int[] wallpaperIds, Context context, OnWallpaperClickListener listener) {
        this.wallpaperIds = wallpaperIds;
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_wallpaper, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int wallpaperId = wallpaperIds[position];

        // Load thumbnail efficiently
        holder.imageView.setImageResource(wallpaperId);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWallpaperClick(wallpaperId);
            }
        });
    }

    @Override
    public int getItemCount() {
        return wallpaperIds.length;
    }

    @Override
    public long getItemId(int position) {
        return wallpaperIds[position];
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.wallpaperImage);
        }
    }
}