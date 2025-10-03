package com.srikanth.glasscaptionsviz.viz;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

public class AudioEngine implements Runnable {
    private static final String TAG = "GCViz";
    private final Context ctx;
    private volatile boolean running = false;
    private Thread thread;
    private AudioRecord recorder;
    private int sampleRate = 16000; // will be overridden if unsupported
    private int bufSize = 2048;

    private final CopyOnWriteArrayList<SpectrogramSink> spectrogramSinks = new CopyOnWriteArrayList<SpectrogramSink>();
    private final CopyOnWriteArrayList<LoudnessListener> loudnessListeners = new CopyOnWriteArrayList<LoudnessListener>();
    private final CopyOnWriteArrayList<WaveformListener> waveformListeners = new CopyOnWriteArrayList<WaveformListener>();
    private final FFT fft = new FFT(1024);

    // Loudness and waveform tracking
    private float[] loudnessBuffer = new float[50]; // Rolling buffer for smoothing
    private int loudnessIndex = 0;
    private float[] currentWaveform;
    private boolean isRecordingWaveform = false;

    public AudioEngine(Context ctx, int preferredSampleRate) {
        this.ctx = ctx;
        this.sampleRate = preferredSampleRate;
        currentWaveform = new float[1024];
    }

    public void addSpectrogramSink(SpectrogramSink s){
        spectrogramSinks.add(s);
        // If the sink is also a loudness listener, add it automatically
        if (s instanceof LoudnessListener) {
            loudnessListeners.add((LoudnessListener) s);
        }
    }

    // NEW METHODS - These were missing!
    public void addLoudnessListener(LoudnessListener l){ loudnessListeners.add(l); }
    public void addWaveformListener(WaveformListener l){ waveformListeners.add(l); }

    public void startWaveformRecording() {
        isRecordingWaveform = true;
        Log.d(TAG, "Started waveform recording");
    }

    public void stopWaveformRecording() {
        isRecordingWaveform = false;
        Log.d(TAG, "Stopped waveform recording");
        // Notify listeners with final waveform
        if (!waveformListeners.isEmpty()) {
            float[] waveformCopy = currentWaveform.clone();
            for (WaveformListener l : waveformListeners) {
                l.onWaveformComplete(waveformCopy);
            }
        }
    }

    public void start() {
        Log.d(TAG, "AudioEngine.start");
        if (running) return;
        running = true;
        thread = new Thread(this, "AudioEngine");
        thread.start();
    }

    public void stop() {
        Log.d(TAG, "AudioEngine.stop");
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignore) {} }
        safeRelease();
    }

    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        if (!initRecorder()) {
            Log.e(TAG, "AudioRecord init failed for all tried rates/sources");
            return; // spectrogram will stay still
        }

        short[] buffer = new short[1024];
        double[] window = new double[buffer.length];
        for (int i=0;i<window.length;i++) {
            window[i] = 0.5 * (1 - Math.cos(2*Math.PI*i/(window.length-1))); // Hann
        }

        try {
            // Add delay before starting recording to ensure initialization
            Thread.sleep(100);
            recorder.startRecording();
            Log.d(TAG, "AudioRecord startRecording @ " + sampleRate + " Hz, buf=" + bufSize);

            // Check recording state
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to start recording! State: " + recorder.getRecordingState());
                return;
            }

            int zeroCount = 0;
            int totalReads = 0;

            while (running) {
                int n;
                try {
                    n = recorder.read(buffer, 0, buffer.length);
                } catch (Throwable t) {
                    Log.e(TAG, "read error", t);
                    break;
                }

                if (n <= 0) {
                    Log.w(TAG, "AudioRecord.read returned: " + n);
                    continue;
                }

                totalReads++;

                // Calculate loudness (RMS) and track peaks
                float rms = 0f;
                int absSum = 0;
                int maxVal = 0;
                for (int i = 0; i < n; i++) {
                    int abs = Math.abs(buffer[i]);
                    absSum += abs;
                    rms += buffer[i] * buffer[i];
                    if (abs > maxVal) maxVal = abs;
                }
                rms = (float) Math.sqrt(rms / n) / 32768f; // Normalize to 0-1

                // Smooth loudness using rolling buffer
                loudnessBuffer[loudnessIndex] = rms;
                loudnessIndex = (loudnessIndex + 1) % loudnessBuffer.length;
                float smoothLoudness = 0f;
                for (float l : loudnessBuffer) smoothLoudness += l;
                smoothLoudness /= loudnessBuffer.length;

                // Update loudness listeners
                if (!loudnessListeners.isEmpty()) {
                    for (LoudnessListener l : loudnessListeners) {
                        l.onLoudnessUpdate(smoothLoudness);
                    }
                }

                // Store waveform if recording
                if (isRecordingWaveform) {
                    int len = Math.min(currentWaveform.length, n);
                    for (int i = 0; i < len; i++) {
                        currentWaveform[i] = buffer[i] / 32768f; // Normalize
                    }
                }

                // Debug audio detection
                if (absSum == 0) {
                    zeroCount++;
                    if (zeroCount % 50 == 0) { // Log every 50th zero buffer to avoid spam
                        Log.w(TAG, "Audio buffer is all zeros (count: " + zeroCount + "/" + totalReads + ")");
                        Log.w(TAG, "Recording state: " + recorder.getRecordingState() +
                                ", Audio state: " + recorder.getState());
                    }
                } else {
                    if (zeroCount > 0) {
                        Log.i(TAG, "Audio detected! Max value: " + maxVal + ", RMS: " + rms +
                                " (after " + zeroCount + " zero buffers)");
                        zeroCount = 0; // Reset counter when we get audio
                    }
                }

                // FFT for spectrogram
                int N = Math.min(n, fft.size);
                double[] re = new double[fft.size];
                double[] im = new double[fft.size];
                for (int i=0;i<N;i++) re[i] = buffer[i] / 32768.0 * window[i];
                fft.fft(re, im);
                float[] mags = new float[fft.size/2];
                for (int i=0;i<mags.length;i++) {
                    mags[i] = (float)Math.sqrt(re[i]*re[i] + im[i]*im[i]);
                }

                if (!spectrogramSinks.isEmpty()) {
                    for (SpectrogramSink s : spectrogramSinks) s.onSpectrogramColumn(mags);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Audio thread error", t);
        } finally {
            try { if (recorder != null) recorder.stop(); } catch (Exception ignore) {}
            safeRelease();
        }
    }

    private boolean initRecorder() {
        // Try sources in order of preference for Glass EE
        final int[] SOURCES = new int[] {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT
        };
        final int[] RATES = new int[] { 44100, sampleRate, 22050, 16000, 11025, 8000 };

        for (int src : SOURCES) {
            for (int rate : RATES) {
                int min = AudioRecord.getMinBufferSize(rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) {
                    Log.w(TAG, "getMinBufferSize returned " + min + " for " + rate + " Hz");
                    continue;
                }

                // Use larger buffer to avoid dropouts
                int tryBuf = Math.max(min * 4, 4096);

                try {
                    Log.d(TAG, "Trying AudioRecord: src=" + getSourceName(src) + " rate=" + rate + " buf=" + tryBuf);

                    AudioRecord r = new AudioRecord(src, rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            tryBuf);

                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        // Test if we can actually read from it
                        short[] testBuffer = new short[128];
                        r.startRecording();
                        Thread.sleep(50); // Give it time to start
                        int testRead = r.read(testBuffer, 0, testBuffer.length);
                        r.stop();

                        if (testRead > 0) {
                            // success
                            safeRelease();
                            recorder = r;
                            sampleRate = rate;
                            bufSize = tryBuf;
                            Log.i(TAG, "AudioRecord SUCCESS: src=" + getSourceName(src) +
                                    " rate=" + rate + " buf=" + tryBuf + " testRead=" + testRead);
                            return true;
                        } else {
                            Log.w(TAG, "AudioRecord test read failed: " + testRead);
                            r.release();
                        }
                    } else {
                        Log.w(TAG, "AudioRecord STATE_UNINITIALIZED: src=" + getSourceName(src) + " rate=" + rate);
                        r.release();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "AudioRecord init exception: src=" + getSourceName(src) + " rate=" + rate, t);
                }
            }
        }
        return false;
    }

    private String getSourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            default: return "UNKNOWN(" + source + ")";
        }
    }

    private void safeRelease() {
        try {
            if (recorder != null) {
                recorder.release();
                Log.d(TAG, "AudioRecord released");
            }
        } catch (Exception ignore) {}
        recorder = null;
    }

    // INTERFACES - These were missing!
    public interface SpectrogramSink { void onSpectrogramColumn(float[] mags); }
    public interface LoudnessListener { void onLoudnessUpdate(float loudness); }
    public interface WaveformListener { void onWaveformComplete(float[] waveform); }
}