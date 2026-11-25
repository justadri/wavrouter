package com.justadri.wavrouter.audio

import android.media.MediaRecorder

/** Capture presets. These map to AudioSource values and decide which platform pre-processing runs. */
enum class InputPreset(val source: Int, val label: String) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Voice recognition (ASR)"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "Unprocessed"),
    MIC(MediaRecorder.AudioSource.MIC, "Mic (default)"),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE, "Voice performance (live)"),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "Voice communication (AEC/NS)"),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "Camcorder"),
}

enum class RecordTap { RAW, PROCESSED }

/** Settings that require an engine restart. */
data class EngineConfig(
    val inputId: Int,
    val outputId: Int?, // null = no monitor output
    val preset: InputPreset,
    val sampleRate: Int,
    val lowLatency: Boolean,
)

/** Settings applied live. Platform effect flags: null = leave the preset's default. */
data class DspParams(
    val gainDb: Float = 0f,
    val hpf: Boolean = false,
    val eqOn: Boolean = false,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val aec: Boolean? = null,
    val ns: Boolean? = null,
    val agc: Boolean? = null,
    val recordTap: RecordTap = RecordTap.RAW,
)

data class MeterReading(
    val peakDb: Float = -120f,
    val rmsDb: Float = -120f,
    val floorDb: Float = -120f,
    val clipped: Boolean = false,
)

data class EffectsState(
    val aecAvail: Boolean = false,
    val aecOn: Boolean = false,
    val nsAvail: Boolean = false,
    val nsOn: Boolean = false,
    val agcAvail: Boolean = false,
    val agcOn: Boolean = false,
)

data class EngineStats(
    val running: Boolean = false,
    val routedIn: String = "",
    val routedOut: String = "",
    val sampleRate: Int = 0,
    val chunkFrames: Int = 0,
    val outBufferFrames: Int = 0,
    val underruns: Int = 0,
    val outDropped: Long = 0,
    val recSeconds: Double = 0.0,
    val recDropped: Int = 0,
    val micInfo: String = "",
    val effects: EffectsState = EffectsState(),
    val error: String? = null,
)
