package com.srikanth.glasscaptionsviz.viz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

public class SpectrogramView extends View implements AudioEngine.SpectrogramSink, AudioEngine.LoudnessListener {
    private static final String TAG = "SpectrogramView";
    private Bitmap bmp;
    private int cols = 256;   // time axis
    private int rows = 128;   // frequency bins
    private int writeCol = 0;

    // Loudness meter
    private float currentLoudness = 0f;
    private float peakLoudness = 0f;
    private long peakTime = 0;
    private Paint loudnessPaint;
    private Paint peakPaint;
    private Paint loudnessBgPaint;
    private Paint textPaint;

    // Waveform persistence
    private float[] persistentWaveform;
    private boolean showPersistentWaveform = false;
    private Paint waveformPaint;

    // Constants
    private static final int LOUDNESS_METER_WIDTH = 15; // Much smaller - auto-adjusts based on screen
    private static final long PEAK_HOLD_TIME = 1000; // ms

    public SpectrogramView(Context c) {
        super(c);
        init();
        Log.d(TAG, "SpectrogramView created with single parameter constructor");
    }

    public SpectrogramView(Context c, AttributeSet a) {
        super(c, a);
        init();
        Log.d(TAG, "SpectrogramView created with AttributeSet constructor");
    }

    private void init() {
        Log.d(TAG, "Initializing SpectrogramView");
        // Loudness meter paints
        loudnessPaint = new Paint();
        loudnessPaint.setAntiAlias(true);

        peakPaint = new Paint();
        peakPaint.setColor(0xFFFF0000); // Red for peaks
        peakPaint.setAntiAlias(true);

        loudnessBgPaint = new Paint();
        loudnessBgPaint.setColor(0xFF222222); // Dark gray background
        loudnessBgPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(12f);
        textPaint.setAntiAlias(true);

        // Waveform paint
        waveformPaint = new Paint();
        waveformPaint.setColor(0xFF222222); // Semi-transparent white
        waveformPaint.setStrokeWidth(2f);
        waveformPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Log.d(TAG, "Size changed: " + w + "x" + h);
        // Auto-adjust loudness meter width based on screen size
        int adjustedMeterWidth = Math.min(LOUDNESS_METER_WIDTH + w/100, w/20); // Scale with screen

        // Reserve space for loudness meter
        int spectrogramWidth = w - adjustedMeterWidth - 10;
        cols = Math.max(64, Math.min(spectrogramWidth, 1024));
        rows = Math.max(64, Math.min(h, 512));
        bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);

        // Initialize bitmap with pure black
        bmp.eraseColor(0xFF222222);
        writeCol = 0;

        // Initialize persistent waveform
        persistentWaveform = new float[cols];
        Log.d(TAG, "Initialized with cols=" + cols + ", rows=" + rows + ", meterWidth=" + adjustedMeterWidth);
    }

    @Override
    public void onSpectrogramColumn(float[] mags) {
        if (bmp == null || mags == null || mags.length == 0) return;

        // Clear column: TRUE BLACK (not dark gray)
        for (int r = 0; r < rows; r++) {
            bmp.setPixel(writeCol, r, 0xFF000000);
        }

        // Parameters for dB scaling
        final float eps = 1e-12f;
        final float minDb = -60f, maxDb = -5f;
        final float gateDb = -22f;  // below this, draw black

        int N = mags.length;
        for (int bi = 0; bi < rows && bi < N; bi++) {
            float mag = mags[bi];
            float dB = 20f * (float)Math.log10(Math.max(mag, eps));
            if (dB < gateDb) continue; // keep black

            float t = (dB - minDb) / (maxDb - minDb); // 0..1
            t = Math.max(0f, Math.min(1f, t));
            // Small floor to avoid purple haze at near-silence
            if (t < 0.06f) continue;

            int color = vibrantPalette(t);
            bmp.setPixel(writeCol, rows - 1 - bi, color);
        }

        writeCol = (writeCol + 1) % cols;
        postInvalidate();
    }
    @Override
    public void onLoudnessUpdate(float loudnessLinear) {
        // Convert linear RMS [0..1] to dBFS
        final float eps = 1e-6f;
        float db = 20f * (float)Math.log10(Math.max(loudnessLinear, eps)); // [-inf..0]
        // Map [-60..0] dB -> [0..1]
        float norm = (db + 60f) / 60f;
        currentLoudness = Math.max(0f, Math.min(1f, norm));

        // Peak hold logic unchanged
        if (currentLoudness > peakLoudness) {
            peakLoudness = currentLoudness;
            peakTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - peakTime > PEAK_HOLD_TIME) {
            peakLoudness *= 0.95f;
        }
        postInvalidate();
    }

    public void showWaveformForSentence(float[] waveformData) {
        Log.d(TAG, "Showing waveform for sentence");
        if (persistentWaveform != null && waveformData != null) {
            int len = Math.min(persistentWaveform.length, waveformData.length);
            System.arraycopy(waveformData, 0, persistentWaveform, 0, len);
            showPersistentWaveform = true;
            postInvalidate();
        }
    }

    public void clearWaveform() {
        Log.d(TAG, "Clearing waveform");
        showPersistentWaveform = false;
        postInvalidate();
    }

    // Enhanced color palette for vibrant visualization like your image
    private int vibrantPalette(float t) {
        float r, g, b;

        if (t < 0.2f) { // Deep blue/purple
            float u = t / 0.2f;
            r = 0.2f * u;
            g = 0f;
            b = 0.8f + 0.2f * u;
        } else if (t < 0.4f) { // Blue to cyan
            float u = (t - 0.2f) / 0.2f;
            r = 0.2f + 0.3f * u;
            g = 0.6f * u;
            b = 1f;
        } else if (t < 0.6f) { // Cyan to green
            float u = (t - 0.4f) / 0.2f;
            r = 0.5f - 0.5f * u;
            g = 0.6f + 0.4f * u;
            b = 1f - 0.5f * u;
        } else if (t < 0.8f) { // Green to yellow
            float u = (t - 0.6f) / 0.2f;
            r = 0f + u;
            g = 1f;
            b = 0.5f - 0.5f * u;
        } else { // Yellow to white/red
            float u = (t - 0.8f) / 0.2f;
            r = 1f;
            g = 1f - 0.3f * u;
            b = u * 0.8f;
        }

        int R = (int)(r * 255f);
        int G = (int)(g * 255f);
        int B = (int)(b * 255f);
        return 0xFF000000 | (R << 16) | (G << 8) | B;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // IMPORTANT: Fill background with black first
        canvas.drawColor(0xFF000000);

        if (bmp == null) return;

        // Auto-adjust meter width based on screen size
        int adjustedMeterWidth = Math.min(LOUDNESS_METER_WIDTH + getWidth()/100, getWidth()/20);

        // Draw spectrogram (leave space for loudness meter)
        int spectrogramWidth = getWidth() - adjustedMeterWidth - 10;

        // Make sure bitmap itself starts with black pixels
        Rect srcRect = new Rect(0, 0, cols, rows);
        Rect dstRect = new Rect(0, 0, spectrogramWidth, getHeight());
        canvas.drawBitmap(bmp, srcRect, dstRect, null);

        // Draw persistent waveform if active
        if (showPersistentWaveform && persistentWaveform != null) {
            drawWaveform(canvas, spectrogramWidth);
        }

        // Draw loudness meter with adjusted width
        drawLoudnessMeter(canvas, spectrogramWidth + 5, adjustedMeterWidth);
    }

    private void drawWaveform(Canvas canvas, int spectrogramWidth) {
        int centerY = getHeight() / 2;
        int maxAmplitude = getHeight() / 4;

        for (int i = 1; i < persistentWaveform.length && i < spectrogramWidth; i++) {
            float x1 = (float)(i - 1) * spectrogramWidth / persistentWaveform.length;
            float y1 = centerY + persistentWaveform[i - 1] * maxAmplitude;
            float x2 = (float)i * spectrogramWidth / persistentWaveform.length;
            float y2 = centerY + persistentWaveform[i] * maxAmplitude;

            canvas.drawLine(x1, y1, x2, y2, waveformPaint);
        }
    }

    private void drawLoudnessMeter(Canvas canvas, int x, int meterWidth) {
        int meterHeight = getHeight() - 40; // Leave margin top/bottom
        int meterY = 20;

        // Background
        RectF bgRect = new RectF(x, meterY, x + meterWidth, meterY + meterHeight);
        canvas.drawRoundRect(bgRect, 4, 4, loudnessBgPaint);

        // Current level (gradient from green to red)
        if (currentLoudness > 0) {
            float levelHeight = currentLoudness * meterHeight;

            // Color based on level
            if (currentLoudness < 0.5f) {
                // Green to yellow
                float ratio = currentLoudness * 2f;
                loudnessPaint.setColor(interpolateColor(0xFF00FF00, 0xFFFFFF00, ratio));
            } else {
                // Yellow to red
                float ratio = (currentLoudness - 0.5f) * 2f;
                loudnessPaint.setColor(interpolateColor(0xFFFFFF00, 0xFFFF0000, ratio));
            }

            RectF levelRect = new RectF(x + 1, meterY + meterHeight - levelHeight,
                    x + meterWidth - 1, meterY + meterHeight - 1);
            canvas.drawRoundRect(levelRect, 2, 2, loudnessPaint);
        }

        // Peak indicator
        if (peakLoudness > 0) {
            float peakY = meterY + meterHeight - (peakLoudness * meterHeight);
            canvas.drawRect(x + 1, peakY - 1, x + meterWidth - 1, peakY + 1, peakPaint);
        }

        // Only draw scale markers if meter is wide enough
        if (meterWidth > 20) {
            textPaint.setTextSize(8f); // Smaller text
            for (int i = 0; i <= 4; i++) {
                float y = meterY + (i * meterHeight / 4f);
                String label = String.valueOf(100 - (i * 25));
                canvas.drawText(label, x + meterWidth + 2, y + 4, textPaint);
            }
        }
    }

    private int interpolateColor(int color1, int color2, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));

        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int)(r1 + (r2 - r1) * ratio);
        int g = (int)(g1 + (g2 - g1) * ratio);
        int b = (int)(b1 + (b2 - b1) * ratio);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public float getCurrentLoudness() {
        return currentLoudness;
    }
}