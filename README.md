# WavRouter

Android 12+ (minSdk 31) mic → DSP → speaker monitor and recorder, built for A/B testing
external mic setups for a voice assistant. Kotlin + Jetpack Compose, no native code yet.

## Build & install (pegasus)

```fish
./scripts/build-install.fish          # assembleDebug + adb install + launch
./scripts/pull-recordings.fish ./rec  # adb pull every WAV into ./rec
```

You need `ANDROID_HOME` (or `local.properties` with `sdk.dir`), JDK 17+, and platform 36 +
build-tools 36.0.0. You can also open the folder in Android Studio.

## What the MVP does

- **Input picker** for every capture device the platform exposes (built-in mic(s), wired,
  USB, BT SCO, BLE headset, line). It shows each device's advertised rates and channel counts.
- **Output picker** (speaker, earpiece, wired, USB, A2DP, SCO, BLE, HDMI…) or *None* for
  meter/record only.
- **Capture preset**: VOICE_RECOGNITION (default, what most assistants use), UNPROCESSED,
  MIC, VOICE_PERFORMANCE, VOICE_COMMUNICATION, CAMCORDER.
- **Sample rate**: 16k / 24k / 44.1k / 48k, mono.
- **Low-latency mode**: fast-track AudioTrack with a burst-sized loop and a 2-burst output buffer.
- **Meters**: peak / RMS / running noise floor (dBFS) for the raw input and the post-DSP output,
  with a latching clip indicator.
- **App DSP**: gain (−24…+36 dB with a soft limiter), 80 Hz high-pass, 3-band EQ.
- **Platform effects**: AEC / NS / AGC switches. They show the preset's real default once the
  engine is running, and are greyed out if the device doesn't provide them.
- **Recording**: 16-bit WAV. The tap is selectable: **Raw capture**, which is what an assistant
  using the same preset would hear, or **Post-DSP**. The file name encodes timestamp, input,
  preset and rate, and the full settings snapshot is embedded as a WAV INFO comment
  (`ffprobe file.wav` shows it).
- **Recordings tab**: play/stop, rename, delete, share (FileProvider).
- **Feedback guard**: built-in mic + built-in speaker/earpiece never monitors.
- Engine stats: routed in/out device (what Android actually picked), buffer sizes,
  underruns, dropped frames, and active `MicrophoneInfo` (position, directionality, sensitivity).

Recordings live at `/sdcard/Android/data/com.justadri.wavrouter/files/Music/`.

## Known limits (MVP)

- **No foreground service.** The screen stays on while the engine runs. If you background the
  app, Android silences capture.
- **Concurrent capture**: Android gives the mic to one normal app at a time, and the foreground
  app wins. You can't run WavRouter and the assistant on the same mic simultaneously. Record
  here, then test the assistant separately.
- **BT mic** forces communication mode (SCO/LE). Route output to the same headset; mixed
  BT-in / other-out is untested.
- **Earpiece** output only works under communication mode, which the app enables automatically.
- `setPreferredDevice` is a *preference*. Check the "in →/out →" line to see what you actually got.
- Mono only.

See [PLAN.md](PLAN.md) for the roadmap.
