package com.justadri.wavrouter.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MicrophoneInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * AudioRecord -> DSP -> AudioTrack loop on a dedicated thread.
 *
 * The blocking read paces the loop on the input clock. Output writes are non-blocking
 * into a small buffer, so clock drift between devices shows up as dropped frames or
 * underruns instead of growing latency.
 *
 * start/stop/effect changes happen on the main thread. Only the loop touches the meters
 * and DSP state.
 */
class AudioEngine(context: Context) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)

    private val _inputMeter = MutableStateFlow(MeterReading())
    private val _outputMeter = MutableStateFlow(MeterReading())
    private val _stats = MutableStateFlow(EngineStats())
    val inputMeter: StateFlow<MeterReading> = _inputMeter.asStateFlow()
    val outputMeter: StateFlow<MeterReading> = _outputMeter.asStateFlow()
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    @Volatile var dsp: DspParams = DspParams()
        set(value) {
            field = value
            applyEffects(value)
        }
    @Volatile var monitorEnabled = true
    @Volatile private var clipResetRequested = false
    @Volatile private var running = false
    @Volatile private var sink: WavSink? = null

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var commModeSet = false
    private var sampleRate = 0

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(cfg: EngineConfig) {
        stop()
        val inDev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == cfg.inputId }
            ?: error("Input device is gone")
        val outDev = cfg.outputId?.let { id ->
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                ?: error("Output device is gone")
        }

        try {
            val commTarget = findCommTarget(inDev, outDev)
            if (commTarget != null) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!am.setCommunicationDevice(commTarget)) Log.w(TAG, "setCommunicationDevice refused")
                commModeSet = true
            }

            val sr = cfg.sampleRate
            val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            val chunk = if (cfg.lowLatency) burst else maxOf(burst, sr / 50)
            sampleRate = sr

            val rec = buildRecord(cfg, sr, chunk).also { record = it }
            rec.setPreferredDevice(inDev)
            val trk = outDev?.let { dev ->
                buildTrack(cfg, sr, chunk, commTarget != null).also { track = it; it.setPreferredDevice(dev) }
            }

            val sid = rec.audioSessionId
            aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(sid) }.getOrNull() else null
            ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sid) }.getOrNull() else null
            agc = if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(sid) }.getOrNull() else null

            _stats.value = EngineStats(
                running = true,
                sampleRate = sr,
                chunkFrames = chunk,
                outBufferFrames = trk?.bufferSizeInFrames ?: 0,
                routedOut = if (trk == null) "none" else "",
            )
            applyEffects(dsp)

            running = true
            thread = Thread({ loop(rec, trk, sr, chunk) }, "wavrouter-audio").apply { start() }
        } catch (t: Throwable) {
            running = false
            releaseAll()
            throw t
        }
    }

    @Synchronized
    fun stop() {
        stopRecording()
        running = false
        runCatching { record?.stop() } // unblocks a pending read()
        thread?.join(1500)
        thread = null
        releaseAll()
        _stats.update { it.copy(running = false) }
    }

    @Synchronized
    fun startRecording(file: File, comment: String) {
        check(running) { "Engine not running" }
        stopRecording()
        sink = WavSink(file, sampleRate, comment)
    }

    @Synchronized
    fun stopRecording(): File? {
        val s = sink ?: return null
        sink = null
        s.close()
        return s.file
    }

    fun resetClip() {
        clipResetRequested = true
    }

    // ---------------------------------------------------------------- internals

    @SuppressLint("MissingPermission")
    private fun buildRecord(cfg: EngineConfig, sr: Int, chunk: Int): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBytes > 0) { "Capture at $sr Hz not supported" }
        val rec = AudioRecord.Builder()
            .setAudioSource(cfg.preset.source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            // Big buffer is headroom, not latency: we read as soon as data is there.
            .setBufferSizeInBytes(maxOf(minBytes, chunk * 2) * 4)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord init failed (${cfg.preset.name} @ $sr Hz)")
        }
        return rec
    }

    private fun buildTrack(cfg: EngineConfig, sr: Int, chunk: Int, comm: Boolean): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBytes > 0) { "Playback at $sr Hz not supported" }
        val attrs = AudioAttributes.Builder()
            .setUsage(if (comm) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val trk = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(if (cfg.lowLatency) minBytes else minBytes * 2)
            .setPerformanceMode(
                if (cfg.lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
            )
            .build()
        if (trk.state != AudioTrack.STATE_INITIALIZED) {
            trk.release()
            error("AudioTrack init failed @ $sr Hz")
        }
        if (cfg.lowLatency) trk.setBufferSizeInFrames(chunk * 2)
        return trk
    }

    private fun findCommTarget(inDev: AudioDeviceInfo, outDev: AudioDeviceInfo?): AudioDeviceInfo? {
        val avail = am.availableCommunicationDevices
        fun match(d: AudioDeviceInfo) =
            avail.firstOrNull { it.type == d.type && it.address == d.address } ?: avail.firstOrNull { it.type == d.type }
        return when {
            outDev != null && outDev.type in Devices.COMM_OUTPUT_TYPES -> match(outDev)
            inDev.type in Devices.COMM_INPUT_TYPES -> match(inDev)
            else -> null
        }
    }

    private fun applyEffects(p: DspParams) {
        p.aec?.let { v -> aec?.let { e -> runCatching { e.setEnabled(v) } } }
        p.ns?.let { v -> ns?.let { e -> runCatching { e.setEnabled(v) } } }
        p.agc?.let { v -> agc?.let { e -> runCatching { e.setEnabled(v) } } }
        val st = EffectsState(
            aecAvail = aec != null, aecOn = aec?.let { runCatching { it.enabled }.getOrNull() } ?: false,
            nsAvail = ns != null, nsOn = ns?.let { runCatching { it.enabled }.getOrNull() } ?: false,
            agcAvail = agc != null, agcOn = agc?.let { runCatching { it.enabled }.getOrNull() } ?: false,
        )
        _stats.update { it.copy(effects = st) }
    }

    private fun releaseAll() {
        listOfNotNull(aec, ns, agc).forEach { runCatching { it.release() } }
        aec = null; ns = null; agc = null
        record?.let { runCatching { it.release() } }
        record = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        if (commModeSet) {
            runCatching { am.clearCommunicationDevice() }
            runCatching { am.mode = AudioManager.MODE_NORMAL }
            commModeSet = false
        }
    }

    private fun loop(rec: AudioRecord, trk: AudioTrack?, sr: Int, chunk: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chain = DspChain()
        val inM = LevelMeter(sr)
        val outM = LevelMeter(sr)
        val inBuf = ShortArray(chunk)
        val f = FloatArray(chunk)
        val outBuf = ShortArray(chunk)
        var outDropped = 0L
        var lastStats = 0L
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Capture did not start (mic busy?)")
            trk?.play()
            val mics = runCatching { rec.activeMicrophones.joinToString("; ", transform = ::describeMic) }.getOrDefault("")
            _stats.update { it.copy(micInfo = mics) }

            while (running) {
                val n = rec.read(inBuf, 0, chunk, AudioRecord.READ_BLOCKING)
                if (n < 0) error("Capture error $n")
                if (n == 0) continue
                if (clipResetRequested) {
                    inM.clipped = false; outM.clipped = false; clipResetRequested = false
                }

                for (i in 0 until n) f[i] = inBuf[i] / 32768f
                inM.add(f, n)?.let { _inputMeter.value = it }

                val p = dsp
                val s = sink
                if (s != null && p.recordTap == RecordTap.RAW) s.offer(inBuf, n)

                chain.configure(p, sr)
                chain.process(f, n, p)
                outM.add(f, n)?.let { _outputMeter.value = it }
                for (i in 0 until n) outBuf[i] = (softClip(f[i]) * 32767f).toInt().toShort()

                if (s != null && p.recordTap == RecordTap.PROCESSED) s.offer(outBuf, n)

                if (trk != null && monitorEnabled) {
                    val w = trk.write(outBuf, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                    if (w in 0 until n) outDropped += (n - w)
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastStats >= 250) {
                    lastStats = now
                    _stats.update {
                        it.copy(
                            routedIn = rec.routedDevice?.let(Devices::label) ?: "?",
                            routedOut = if (trk == null) "none" else trk.routedDevice?.let(Devices::label) ?: "?",
                            underruns = trk?.underrunCount ?: 0,
                            outDropped = outDropped,
                            recSeconds = s?.seconds ?: 0.0,
                            recDropped = s?.droppedChunks ?: 0,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio loop", t)
            if (running) _stats.update { it.copy(error = t.message ?: t.toString()) }
        }
    }

    private fun describeMic(m: MicrophoneInfo): String {
        val dir = when (m.directionality) {
            MicrophoneInfo.DIRECTIONALITY_OMNI -> "omni"
            MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "figure-8"
            MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
            MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hypercardioid"
            MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "supercardioid"
            else -> "dir?"
        }
        val loc = when (m.location) {
            MicrophoneInfo.LOCATION_MAINBODY -> "body"
            MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "movable"
            MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
            else -> "loc?"
        }
        val sens = if (m.sensitivity == MicrophoneInfo.SENSITIVITY_UNKNOWN) "" else
            ", %.1f dBFS@94dBSPL".format(m.sensitivity)
        val name = m.description.ifBlank { "mic#${m.id}" }
        return "$name ($loc, $dir$sens)"
    }

    private companion object {
        const val TAG = "WavRouter"
    }
}
