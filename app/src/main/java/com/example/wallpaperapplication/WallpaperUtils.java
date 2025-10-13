package com.example.wallpaperapplication;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

public class WallpaperUtils {

    /**
     * Efficiently decode a bitmap from resources with proper sampling
     * to avoid OutOfMemoryError and reduce processing time
     */
    public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                         int reqWidth, int reqHeight) {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = false;
        options.inPurgeable = true;
        options.inInputShareable = true;

        return BitmapFactory.decodeResource(res, resId, options);
    }

    /**
     * Calculate the best sample size to reduce bitmap dimensions
     * while maintaining quality
     */
    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * Get optimized target dimensions based on screen density
     */
    public static int[] getOptimalDimensions(Resources res) {
        int densityDpi = res.getDisplayMetrics().densityDpi;

        // Adjust target size based on screen density
        if (densityDpi >= 480) { // XXHDPI or higher
            return new int[]{1440, 2560};
        } else if (densityDpi >= 320) { // XHDPI
            return new int[]{1080, 1920};
        } else if (densityDpi >= 240) { // HDPI
            return new int[]{720, 1280};
        } else {
            return new int[]{540, 960};
        }
    }
}