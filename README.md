# Glass Captions Visualizer v1.0

Glass Captions Visualizer is an Android app for Google Glass (EE/XE) that combines **real-time captions** with a **CAR-FAC-inspired spectrogram**. You get readable captions that persist between utterances and a coloured waterfall that hints at pitch/energy across time.

## Features

- **Continuous captions** (partial + final), captions remain on-screen for reading.
- **Pitch-tinted spectrogram**: low→blue/cyan, mid→green/yellow, high→orange/red.
- **Loudness-driven emphasis**: base 24 sp; last word pops to 32 sp on >15% dB spike vs rolling EMA.
- **Waveform overlay** for the most recent utterance.
- **Wake-lock + BACK to exit** so Glass doesn’t sleep mid-session.
- 
## Build
- Open in Android Studio
- Use **Gradle JDK = 1.8**
- Keep **Gradle 6.1.1 / AGP 4.0.2**
- compile/target SDK 19

## Install & launch
```bash
adb connect <GLASS_IP>:5555
adb -s <GLASS_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <GLASS_IP>:5555 shell am start -n com.srikanth.glasscaptionsviz/.MainActivity
adb -s <GLASS_IP>:5555 logcat GCViz:D AndroidRuntime:E ActivityManager:I *:S
```

## Noise floor

- Spectrogram column skip and pixel gating tuned ≈ **–20 dBFS** equivalent.
- Tweak in `SpectrogramView.onSpectrogramColumn`: `avgEnergy` guard and `norm > 0.15f` pixel threshold.

## Error codes (SpeechRecognizer)

| Code | Meaning                  | Recovery                            |
|:---:|---------------------------|-------------------------------------|
| 1   | Network timeout           | Retry with back-off                 |
| 2   | Network error             | Retry, check internet               |
| 3   | Audio error               | Restart audio/STT                   |
| 4   | Server error              | Retry later                         |
| 5   | Client error              | Fix parameters; avoid double start  |
| 6   | Speech timeout            | Quiet restart, keep caption         |
| 7   | No match                  | Keep caption, restart               |
| 8   | Recognizer busy           | `cancel()` → 400–700 ms → restart   |
| 9   | Insufficient permissions  | Grant `RECORD_AUDIO`                |

## Customise

- Palette: edit `pitchogramPalette(t,pos)` in `SpectrogramView`.
- Emphasis: `DB_SPIKE_RATIO`, `BASE_SP`, `EMPHASIS_SP` in `MainActivity`.
- Silence UI: status can flip to “Listening…” on inactivity; captions aren’t cleared.

## Credits

Colour mapping inspired by Google’s CAR-FAC pitchogram demo. This project does **not** port the cochlear model; it adapts the visual style for an Android Java spectrogram.
