package com.example.wallpaperapplication;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class WallpaperAdapter extends RecyclerView.Adapter<WallpaperAdapter.ViewHolder> {
    private final int[] wallpaperIds;
    private final Context context;

    public WallpaperAdapter(int[] wallpaperIds, Context context) {
        this.wallpaperIds = wallpaperIds;
        this.context = context;
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
        holder.imageView.setImageResource(wallpaperIds[position]);
        holder.itemView.setOnClickListener(v -> {
            // TODO: launch StreamingService/Capture UI
        });
    }

    @Override
    public int getItemCount() {
        return wallpaperIds.length;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.wallpaperImage);
        }
    }
}
