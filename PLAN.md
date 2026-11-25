# WavRouter plan

## Architecture

```
AudioRecord (preset, preferred input)            ──► LevelMeter (raw)
   │ 16-bit mono, READ_BLOCKING, burst chunks           │
   ├──► WavSink [tap = RAW] ──► writer thread ──► WAV (+INFO comment)
   ▼
DspChain: gain → HPF 80 Hz → low shelf / peak / high shelf  ──► LevelMeter (post)
   │ soft clip
   ├──► WavSink [tap = PROCESSED]
   ▼
AudioTrack (preferred output, LOW_LATENCY, WRITE_NON_BLOCKING, 2-burst buffer)
```

- One loop thread (`THREAD_PRIORITY_URGENT_AUDIO`). The blocking read paces it on the input
  clock. A non-blocking write into a tiny output buffer keeps latency bounded when input and
  output clocks drift (USB in → BT out); the cost is the occasional drop or underrun, both counted in stats.
- Live params live in an immutable `DspParams` behind a `@Volatile` reference. The loop
  recomputes biquad coefficients only when the reference changes.
- Routing, preset, rate or latency changes restart the engine (~100 ms) and are locked while recording.
- Platform AEC/NS/AGC objects attach to the AudioRecord session. A `null` override means
  "leave the preset's default".

| File | Role |
|---|---|
| `audio/AudioEngine.kt` | Device setup, comm-mode handling, the loop, stats |
| `audio/Dsp.kt` | Biquads, chain, soft clip, level meter |
| `audio/Wav.kt` | WAV writer / async sink / header + INFO reader |
| `audio/Devices.kt` | Device filtering, labels, capability strings |
| `data/RecordingRepository.kt` | List / rename / delete / share |
| `MainViewModel.kt` | State, device hot-plug, engine lifecycle, playback |
| `ui/*` | Compose screens |

## MVP checklist (target: ~2 h of hands-on)

1. [ ] Build on pegasus, install, and grant permissions
2. [ ] Sanity: USB/wired mic → wired headphones, meters move, monitor audible
3. [ ] Record raw @ VOICE_RECOGNITION 16 kHz, pull with `scripts/pull-recordings.fish`, check with `ffprobe`
4. [ ] Verify routed in/out matches the selection for each of your devices
5. [ ] Try UNPROCESSED vs VOICE_RECOGNITION vs MIC on the same mic and compare the floor/peak readings
6. [ ] Rename / share / delete round-trip
7. [ ] Note device-specific failures (preset × device × rate) in this file

## Phase 2 (pick what earns its keep)

- **A/B helpers for mic placement**: tag/notes field per recording; a "take" counter;
  a fixed-length timed capture (e.g. 10 s) so takes are comparable; CSV export of
  peak/RMS/floor per take.
- **SNR readout**: speech-active RMS vs noise floor (simple energy VAD).
- **Spectrum / spectrogram view** (FFT on a decimated copy of the post-meter buffer).
- **Stereo / multichannel capture** for USB arrays (`CHANNEL_IN_STEREO`, index masks), with per-channel meters.
- **Adjustable noise reduction**: RNNoise via NDK with a wet/dry strength slider
  (RNNoise is 48 kHz-only, so resample for other rates).
- **Foreground service** (`foregroundServiceType="microphone"`) for screen-off use.
- **Opus / FLAC** recording via `MediaCodec` + `MediaMuxer` (Ogg for Opus needs a small muxer).
- **Oboe/AAudio engine** behind the same interface if the Java path's latency isn't enough.
- **Feedback suppression** (adaptive notch) to lift the built-in mic → speaker guard.
- **Mixed BT-mic + non-BT output** routing.
- `setPreferredMicrophoneDirection()` / field-dimension controls for built-in arrays.
- Virtual mic: not possible for a normal APK (needs `CAPTURE_AUDIO_OUTPUT`/system app or root).
