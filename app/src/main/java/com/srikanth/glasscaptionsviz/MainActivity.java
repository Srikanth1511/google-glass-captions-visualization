package com.srikanth.glasscaptionsviz;

import android.app.Activity;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.TextView;
import android.view.WindowManager;
import android.os.PowerManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;




import com.srikanth.glasscaptionsviz.viz.AudioEngine;
import com.srikanth.glasscaptionsviz.viz.SpectrogramView;

import java.util.ArrayList;

public class MainActivity extends Activity implements AudioEngine.WaveformListener {

    private static final String TAG = "GCViz";

    private TextView captions;
    private SpectrogramView spectrogramView;
    private AudioEngine audioEngine;

    private SpeechRecognizer speech;
    private Intent sttIntent;

    // Caption sizing
    private static final float BASE_SP = 24f;      // normal
    private static final float EMPHASIS_SP = 32f;  // larger for emphasized word

    // Emphasis logic (in dB domain)
    private static final float DB_SPIKE_RATIO = 1.15f; // 15% over rolling dB average triggers emphasis
    private static final float EMA_ALPHA = 0.20f;      // dB rolling average smoothing
    private static final float DB_MIN = -10f;          // floor for silence in dBFS - change this to chek the background
    private static final float EPS = 1e-6f;
    // Silence → “Listening…” after 10s with no speech activity
    private static final long SILENCE_MS = 15_000L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable silenceRunnable = new Runnable() {
        @Override public void run() {
            if (captions != null) {
                captions.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SP);
                captions.setText("Listening…");
            }
        }
    };
    private void resetSilenceTimer() {
        ui.removeCallbacks(silenceRunnable);
        ui.postDelayed(silenceRunnable, SILENCE_MS);
    }


    private volatile float emaDb = DB_MIN; // rolling dB average
    private Thread dbEmaThread;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "GCViz:WakeLock");
        try { wakeLock.acquire(); } catch (Throwable ignore) {}

        Log.d(TAG, "onCreate");

        captions = (TextView) findViewById(R.id.captions);
        spectrogramView = (SpectrogramView) findViewById(R.id.spectrogram);

        // Always show some text at base size
        if (captions != null) {
            captions.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SP);
            captions.setText("Listening…");
        }


        // Start audio engine first (so it takes the mic cleanly)
        audioEngine = new AudioEngine(this, 16000);
        if (spectrogramView != null) {
            audioEngine.addSpectrogramSink(spectrogramView);
            audioEngine.addLoudnessListener(spectrogramView); // drives on-screen meter internally
            audioEngine.addWaveformListener(this);            // to overlay per-utterance waveform
        }
        try {
            audioEngine.start();
            Log.d(TAG, "AudioEngine started");
        } catch (Throwable t) {
            Log.e(TAG, "AudioEngine start failed", t);
            if (captions != null) captions.setText("Audio error: " + t.getMessage());
        }

        // Start rolling dB tracker (reads spectrogram's current loudness and converts to dB)
        startDbEmaLoop();

        // Stagger STT to avoid mic contention (AudioRecord grabs mic first)
        new Thread(() -> {
            try { Thread.sleep(900); } catch (InterruptedException ignored) {}
            runOnUiThread(this::startStt);
        }).start();
    }

    // ---------- STT ----------

    private void startStt() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                if (captions != null) captions.setText("Speech service not available");
                Log.w(TAG, "Speech service not available");
                return;
            }
            speech = SpeechRecognizer.createSpeechRecognizer(this);
            speech.setRecognitionListener(new SimpleListener());

            sttIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            sttIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            sttIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            sttIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            sttIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

            speech.startListening(sttIntent);
            Log.d(TAG, "SpeechRecognizer startListening");
        } catch (Throwable t) {
            Log.e(TAG, "SpeechRecognizer setup failed", t);
            if (captions != null) captions.setText("STT error: " + t.getMessage());
        }
    }

    private void restartSttQuick() {
        try { if (speech != null) speech.cancel(); } catch (Exception ignore) {}
        try { if (speech != null) speech.startListening(sttIntent); } catch (Exception ignore) {}
    }

    private void stopStt() {
        try { if (speech != null) speech.cancel(); } catch (Exception ignore) {}
        try { if (speech != null) speech.destroy(); } catch (Exception ignore) {}
        speech = null;
    }

    // ---------- Loudness → dB EMA ----------

    private void startDbEmaLoop() {
        dbEmaThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(80);
                    float curDb = currentDb();
                    emaDb = (1f - EMA_ALPHA) * emaDb + EMA_ALPHA * curDb;
                }
            } catch (InterruptedException ignored) {}
        }, "DbEmaLoop");
        dbEmaThread.start();
    }

    private void stopDbEmaLoop() {
        if (dbEmaThread != null) {
            dbEmaThread.interrupt();
            dbEmaThread = null;
        }
    }

    // Convert spectrogramView's current loudness (expected ~linear 0..1) to dBFS
    private float currentDb() {
        if (spectrogramView == null) return DB_MIN;
        float lin = spectrogramView.getCurrentLoudness(); // your view exposes this; treat as linear
        lin = Math.max(EPS, lin);                         // avoid -inf
        float db = 20f * (float)Math.log10(lin);          // [-inf..0]
        if (Float.isNaN(db) || Float.isInfinite(db)) db = DB_MIN;
        return Math.max(DB_MIN, Math.min(0f, db));
    }

    // ---------- Caption rendering with last-word emphasis ----------

    private void setCaptionWithEmphasis(String fullText) {
        if (captions == null || fullText == null) return;

        // Always show text at base size
        captions.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SP);

        String trimmed = fullText.trim();
        if (trimmed.isEmpty()) { captions.setText(""); return; }

        int lastSpace = trimmed.lastIndexOf(' ');
        float curDb = currentDb();
        boolean emphasize = curDb > (emaDb * DB_SPIKE_RATIO);

        if (lastSpace < 0) {
            // single word
            if (!emphasize) {
                captions.setText(trimmed);
            } else {
                android.text.SpannableString sp = new android.text.SpannableString(trimmed);
                sp.setSpan(new android.text.style.AbsoluteSizeSpan((int)EMPHASIS_SP, true),
                        0, trimmed.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                captions.setText(sp);
            }
            return;
        }

        // Multi-word: bump only the last token
        android.text.SpannableString sp = new android.text.SpannableString(trimmed);
        if (emphasize) {
            sp.setSpan(new android.text.style.AbsoluteSizeSpan((int)EMPHASIS_SP, true),
                    lastSpace + 1, trimmed.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        captions.setText(sp);
    }

    // ---------- Waveform overlay callback ----------

    @Override
    public void onWaveformComplete(float[] waveform) {
        if (spectrogramView != null) {
            spectrogramView.showWaveformForSentence(waveform);
        }
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(silenceRunnable);
        try { if (speech != null) speech.cancel(); } catch (Exception ignore) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (speech != null && sttIntent != null) {
            try { speech.startListening(sttIntent); } catch (Exception ignore) {}
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(silenceRunnable);
        stopDbEmaLoop();
        stopStt();
        if (audioEngine != null) {
            audioEngine.stop();
        }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignore) {}

    }


    // Swipe-down on Glass maps to BACK; do a full app exit so it doesn’t hang around
    private void shutdownAndExit() {
        Log.d(TAG, "shutdownAndExit");
        stopDbEmaLoop();
        stopStt();
        if (audioEngine != null) audioEngine.stop();
        try { finishAffinity(); } catch (Exception ignore) {}
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } catch (Throwable ignore) {}
    }

    @Override
    public void onBackPressed() {
        shutdownAndExit();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            shutdownAndExit();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Recognition Listener ----------

    class SimpleListener implements RecognitionListener {
        private void log(String m){ Log.d(TAG, "STT:" + m); }

        @Override public void onReadyForSpeech(Bundle params) {
            log("ready");
            resetSilenceTimer();
            if (spectrogramView != null) spectrogramView.clearWaveform();
        }

        @Override public void onBeginningOfSpeech() {
            log("begin");
            // Start recording waveform for this utterance
            if (audioEngine != null) audioEngine.startWaveformRecording();
            if (captions != null) captions.setText("…");
            resetSilenceTimer();
        }

        @Override public void onRmsChanged(float rmsdB) {
            // Not used; we compute emphasis from spectrogramView loudness -> dB EMA
        }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() {
            log("end");
            //if (audioEngine != null) audioEngine.stopWaveformRecording();
            resetSilenceTimer();
        }

        @Override public void onError(int error) {
            log("error:" + error);
            if (captions != null) {
                captions.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SP);
                captions.setText("Speech error: " + error);
            }
            //if (audioEngine != null) audioEngine.stopWaveformRecording();
            // quick restart loop for continuous captions
            restartSttQuick();
            resetSilenceTimer();
        }

        @Override public void onResults(Bundle results) {
            log("final results");
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty() && captions != null) {
                setCaptionWithEmphasis(list.get(0));
            }
            //if (audioEngine != null) audioEngine.stopWaveformRecording();
            // brief pause to let user read, then restart
            new Thread(() -> {
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                runOnUiThread(() -> restartSttQuick());
            }).start();
            resetSilenceTimer();
        }

        @Override public void onPartialResults(Bundle partialResults) {
            log("partial");
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty() && captions != null) {
                setCaptionWithEmphasis(list.get(0));
            }
            resetSilenceTimer();
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
