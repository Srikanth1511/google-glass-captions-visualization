package com.srikanth.glasscaptionsviz.viz;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

public class SpectrogramView extends View
        implements AudioEngine.SpectrogramSink, AudioEngine.LoudnessListener {

    private Bitmap bmp;
    private int cols = 640;
    private int rows = 256;
    private int writeCol = 0;

    // Loudness meter (normalized 0..1) + simple peak hold
    private float currentLoudness = 0f;
    private float peakLoudness = 0f;
    private long peakTime = 0L;
    private static final long PEAK_HOLD_TIME = 1000L;

    // Optional waveform overlay
    private float[] lastWaveform = null;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Column buffer to reduce per-pixel JNI calls
    private int[] colBuf;

    // Cache for row→bin mapping (recomputed if FFT size or rows change)
    private int[] rowToBin = null;
    private int cachedBinCount = -1;

    public SpectrogramView(Context c) { super(c); init(); }
    public SpectrogramView(Context c, AttributeSet a) { super(c, a); init(); }
    public SpectrogramView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        ensureBitmap();
    }

    private void ensureBitmap() {
        if (bmp == null || bmp.getWidth() != cols || bmp.getHeight() != rows) {
            bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
            colBuf = new int[rows];
            rowToBin = null;           // will recompute on first column when we know N
            cachedBinCount = -1;
            clearAll();
            writeCol = 0;
        } else if (colBuf == null || colBuf.length != rows) {
            colBuf = new int[rows];
        }
    }

    private void ensureRowMap(int binCount) {
        if (rowToBin != null && cachedBinCount == binCount && rowToBin.length == rows) return;
        rowToBin = new int[rows];
        for (int r = 0; r < rows; r++) {
            // log-ish mapping (more resolution at low freq)
            rowToBin[r] = (int) Math.min(binCount - 1,
                    Math.pow((double) r / (double) rows, 1.25) * binCount);
        }
        cachedBinCount = binCount;
    }

    private void clearAll() {
        if (bmp == null) return;
        int[] black = new int[cols * rows];
        for (int i = 0; i < black.length; i++) black[i] = 0xFF000000;
        bmp.setPixels(black, 0, cols, 0, 0, cols, rows);
        invalidate();
    }

    /** Clear previous utterance waveform overlay. */
    public void clearWaveform() { lastWaveform = null; invalidate(); }

    /** Display final utterance waveform overlay. */
    public void showWaveformForSentence(float[] waveform) {
        lastWaveform = waveform;
        invalidate();
    }

    @Override
    public void onLoudnessUpdate(float loudnessLinear) {
        final float eps = 1e-6f;
        float db = 20f * (float) Math.log10(Math.max(loudnessLinear, eps)); // [-inf..0]
        float norm = (db + 60f) / 60f;                                      // [-60..0] → [0..1]
        currentLoudness = Math.max(0f, Math.min(1f, norm));

        long now = System.currentTimeMillis();
        if (currentLoudness > peakLoudness) {
            peakLoudness = currentLoudness;
            peakTime = now;
        } else if (now - peakTime > PEAK_HOLD_TIME) {
            peakLoudness *= 0.95f;
        }
        postInvalidateOnAnimation();
    }

    @Override
    public void onSpectrogramColumn(float[] mags) {
        ensureBitmap();
        if (bmp == null || mags == null || mags.length == 0) return;

        // Rebuild row→bin map if FFT size changed or on first draw
        ensureRowMap(mags.length);

        // Column-level silence/noise gate (~ -20 dB-ish)
        double sum = 0.0;
        for (float v : mags) sum += v;
        float avgEnergy = (float)(sum / Math.max(1, mags.length));

        // Pre-clear column buffer to black (ARGB)
        for (int i = 0; i < rows; i++) colBuf[i] = 0xFF000000;

        // If the entire column is basically silent, commit black and return
        if (avgEnergy <= 0.0002f) { // Stricter column-level gate
            bmp.setPixels(colBuf, 0, 1, writeCol, 0, 1, rows);
            writeCol = (writeCol + 1) % cols;
            postInvalidateOnAnimation();
            return;
        }

        final int denom = Math.max(1, rows - 1);  // guard rows==1

        for (int r = 0; r < rows; r++) {
            int i0 = rowToBin[r];
            float v = mags[i0];

            // --- Per-bin dB floor: skip anything below ~ -25 dBFS ---
            final float eps = 1e-9f;
            float dbBin = 20f * (float)Math.log10(Math.max(v, eps));
            if (dbBin < -25f) continue;  // Hard dB floor

            // Perceptual amplitude mapping with compression
            float level = (float)Math.log1p(v * 150f);
            float norm = Math.min(level * 0.55f, 1f);
            if (norm < 0f) norm = 0f;

            // Apply gamma for perceptual brightness
            norm = (float)Math.pow(norm, 0.48);

            // Pixel gate (stricter): avoid drawing tiny residuals
            if (norm <= 0.20f) continue;  // Raised threshold to eliminate faint pixels

            // Normalized position (0 = low freq bottom, 1 = high top)
            float pos = (float)r / (float)denom;

            int color = carfacPalette(norm, pos);
            // draw at image bottom for low frequencies
            colBuf[rows - 1 - r] = color;
        }

        // Commit whole column in a single call
        bmp.setPixels(colBuf, 0, 1, writeCol, 0, 1, rows);

        // Advance write pointer
        writeCol = (writeCol + 1) % cols;
        postInvalidateOnAnimation();
    }

    /**
     * CAR-FAC accurate palette - NO baseline brightness, true black for quiet bins.
     * Matches Google's pitchogram hue progression.
     */
    private int carfacPalette(float t, float pos) {
        // Values below 0.20 are already filtered out in onSpectrogramColumn
        // but double-check for safety
        if (t < 0.20f) return 0xFF000000;

        // Map [0.20, 1.0] → [0.0, 1.0] for color intensity
        float intensity = (t - 0.20f) / 0.80f;

        float h, s, v;

        // CAR-FAC frequency-to-hue mapping
        if (pos < 0.15f) {
            // Very low frequencies: deep magenta/purple
            float u = pos / 0.15f;
            h = 290f - u * 20f;          // 290° → 270° (magenta-purple)
            s = 0.80f + u * 0.10f;       // 0.80 → 0.90
        } else if (pos < 0.30f) {
            // Low frequencies: purple → blue
            float u = (pos - 0.15f) / 0.15f;
            h = 270f - u * 40f;          // 270° → 230° (purple-blue)
            s = 0.90f;
        } else if (pos < 0.45f) {
            // Low-mid: blue → cyan
            float u = (pos - 0.30f) / 0.15f;
            h = 230f - u * 50f;          // 230° → 180° (blue-cyan)
            s = 0.88f;
        } else if (pos < 0.60f) {
            // Mid: cyan → green
            float u = (pos - 0.45f) / 0.15f;
            h = 180f - u * 60f;          // 180° → 120° (cyan-green)
            s = 0.85f;
        } else if (pos < 0.75f) {
            // Mid-high: green → yellow/gold
            float u = (pos - 0.60f) / 0.15f;
            h = 120f - u * 60f;          // 120° → 60° (green-yellow)
            s = 0.88f;
        } else {
            // High frequencies: yellow → orange-red
            float u = (pos - 0.75f) / 0.25f;
            h = 60f - u * 45f;           // 60° → 15° (yellow-orange-red)
            s = 0.90f + u * 0.05f;       // 0.90 → 0.95
        }

        // NO baseline - intensity controls everything
        // v goes from 0 (black) to 1.0 (full brightness)
        v = intensity;

        return hsvToRgb(h, s, v);
    }

    /** HSV→RGB with strict clamping (safer than relying on float rounding). */
    private int hsvToRgb(float h, float s, float v) {
        h = h % 360f; if (h < 0f) h += 360f;

        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;

        float r, g, b;
        if (h < 60f)       { r = c; g = x; b = 0f; }
        else if (h < 120f) { r = x; g = c; b = 0f; }
        else if (h < 180f) { r = 0f; g = c; b = x; }
        else if (h < 240f) { r = 0f; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0f; b = c; }
        else               { r = c; g = 0f; b = x; }

        float rf = r + m, gf = g + m, bf = b + m;
        int R = (int)(Math.max(0f, Math.min(1f, rf)) * 255f);
        int G = (int)(Math.max(0f, Math.min(1f, gf)) * 255f);
        int B = (int)(Math.max(0f, Math.min(1f, bf)) * 255f);

        return 0xFF000000 | (R << 16) | (G << 8) | B;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensureBitmap();

        // Background
        canvas.drawColor(0xFF000000);

        // Spectrogram image (fit to view)
        Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        Rect dst = new Rect(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bmp, src, dst, paint);

        // Loudness meter (right)
        int w = getWidth(), h = getHeight();
        int meterW = Math.max(6, w / 64);
        int meterX = w - meterW - 6;
        int meterY = 6;
        int meterH = h - 12;

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x55FFFFFF);
        paint.setStrokeWidth(2f);
        canvas.drawRect(meterX, meterY, meterX + meterW, meterY + meterH, paint);

        int lvlH = (int) (currentLoudness * meterH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99FFFFFF);
        canvas.drawRect(meterX + 2, meterY + (meterH - lvlH),
                meterX + meterW - 2, meterY + meterH - 2, paint);

        int peakH = (int) (peakLoudness * meterH);
        paint.setColor(0xFFFFD54F);
        canvas.drawRect(meterX + 2, meterY + (meterH - peakH) - 2,
                meterX + meterW - 2, meterY + (meterH - peakH) + 2, paint);

        // Waveform overlay
        if (lastWaveform != null && lastWaveform.length > 1) {
            paint.setColor(0x66FFFFFF);
            paint.setStrokeWidth(2f);
            float mid = h * 0.75f;
            float amp = h * 0.10f;
            int N = lastWaveform.length;
            float dx = (float) w / (N - 1);
            float x = 0f;
            for (int i = 1; i < N; i++) {
                float y0 = mid - amp * lastWaveform[i - 1];
                float y1 = mid - amp * lastWaveform[i];
                canvas.drawLine(x, y0, x + dx, y1, paint);
                x += dx;
            }
        }
    }

    public float getCurrentLoudness() { return currentLoudness; }
}