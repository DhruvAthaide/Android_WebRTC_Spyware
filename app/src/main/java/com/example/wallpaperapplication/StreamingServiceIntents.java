package com.example.wallpaperapplication;

import android.content.Context;
import android.content.Intent;

public final class StreamingServiceIntents {
    private StreamingServiceIntents() {}

    public static Intent start(Context ctx) {
        return new Intent(ctx, StreamingService.class);
    }

    public static Intent stop(Context ctx) {
        Intent i = new Intent(ctx, StreamingService.class);
        i.setAction("STOP_STREAMING");
        return i;
    }
}
