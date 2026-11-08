package com.huandou.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * Native blur backdrop for dialogs.
 *
 * <p>Instead of a flat opaque mask we capture the WebView into a heavily
 * downscaled bitmap, run a small box blur (3 passes ~= Gaussian) on it and let
 * the GPU scale it back up.  The result is drawn by a native View that sits
 * ABOVE the WebView but BELOW the dialog window, so it behaves exactly like a
 * native sub-window: web content can never draw over it and it never flickers
 * with the page's own z-index or video surface.</p>
 *
 * <p>Pure Java, no RenderScript, works from API 24.  If the capture fails
 * (some WebView implementations paint into a separate surface) the caller
 * falls back to the translucent tint alone, which still keeps the page
 * visible.</p>
 */
public final class BlurBackdrop {

    private static final int SCALE = 8;
    private static final int RADIUS = 3;
    private static final int PASSES = 3;

    private BlurBackdrop() {
    }

    /** Returns a small blurred bitmap, or null when the view cannot be captured. */
    public static Bitmap capture(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int w = Math.max(1, v.getWidth() / SCALE);
        int h = Math.max(1, v.getHeight() / SCALE);
        Bitmap small;
        try {
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        Canvas c = new Canvas(small);
        c.scale(1f / SCALE, 1f / SCALE);
        try {
            v.draw(c);
        } catch (Throwable t) {
            small.recycle();
            return null;
        }
        if (isEmpty(small)) {
            small.recycle();
            return null;
        }
        Bitmap out = boxBlur(small, RADIUS);
        small.recycle();
        return out;
    }

    private static boolean isEmpty(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 3 || h < 3) return true;
        int first = b.getPixel(0, 0);
        int diffs = 0, samples = 0;
        int sx = Math.max(1, w / 8), sy = Math.max(1, h / 8);
        for (int y = 1; y < h; y += sy) {
            for (int x = 1; x < w; x += sx) {
                samples++;
                if (b.getPixel(x, y) != first) diffs++;
            }
        }
        return diffs < Math.max(2, samples / 12);
    }

    private static Bitmap boxBlur(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < PASSES; p++) {
            horizontal(a, b, w, h, r);
            vertical(b, a, w, h, r);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void horizontal(int[] in, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int al = 0, re = 0, gr = 0, bl = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0) xx = 0;
                    else if (xx >= w) xx = w - 1;
                    int c = in[row + xx];
                    al += (c >>> 24);
                    re += (c >> 16) & 255;
                    gr += (c >> 8) & 255;
                    bl += c & 255;
                    n++;
                }
                out[row + x] = (al / n << 24) | (re / n << 16) | (gr / n << 8) | (bl / n);
            }
        }
    }

    private static void vertical(int[] in, int[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int al = 0, re = 0, gr = 0, bl = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0) yy = 0;
                    else if (yy >= h) yy = h - 1;
                    int c = in[yy * w + x];
                    al += (c >>> 24);
                    re += (c >> 16) & 255;
                    gr += (c >> 8) & 255;
                    bl += c & 255;
                    n++;
                }
                out[y * w + x] = (al / n << 24) | (re / n << 16) | (gr / n << 8) | (bl / n);
            }
        }
    }
}
