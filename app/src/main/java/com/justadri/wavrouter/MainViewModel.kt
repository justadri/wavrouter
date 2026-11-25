package com.justadri.wavrouter

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justadri.wavrouter.audio.AudioEngine
import com.justadri.wavrouter.audio.DeviceOption
import com.justadri.wavrouter.audio.Devices
import com.justadri.wavrouter.audio.DspParams
import com.justadri.wavrouter.audio.EngineConfig
import com.justadri.wavrouter.audio.InputPreset
import com.justadri.wavrouter.data.RecordingItem
import com.justadri.wavrouter.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val inputs: List<DeviceOption> = emptyList(),
    val outputs: List<DeviceOption> = emptyList(),
    val inputId: Int? = null,
    val outputId: Int? = null, // null = none
    val outputChosen: Boolean = false,
    val preset: InputPreset = InputPreset.VOICE_RECOGNITION,
    val sampleRate: Int = 48000,
    val lowLatency: Boolean = true,
    val monitor: Boolean = true,
    val dsp: DspParams = DspParams(),
    val running: Boolean = false,
    val recording: Boolean = false,
    val unprocessedSupported: Boolean = false,
    val message: String? = null,
) {
    val input: DeviceOption? get() = inputs.firstOrNull { it.id == inputId }
    val output: DeviceOption? get() = outputs.firstOrNull { it.id == outputId }

    /** MVP feedback guard: built-in mic into built-in speaker/earpiece is not allowed. */
    val feedbackBlocked: Boolean get() = input?.builtin == true && output?.builtin == true
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val am = app.getSystemService(AudioManager::class.java)
    val engine = AudioEngine(app)
    private val repo = RecordingRepository(app)

    private val _ui = MutableStateFlow(
        UiState(
            unprocessedSupported = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true",
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()
    private var player: MediaPlayer? = null

    val recordingsDir: String get() = repo.dir.absolutePath
    val aecSupported = AcousticEchoCanceler.isAvailable()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDevices()
        refreshRecordings()
        viewModelScope.launch {
            engine.stats.collect { s ->
                if (s.error != null && _ui.value.running) {
                    stopEngine()
                    message("Engine stopped: ${s.error}")
                }
            }
        }
    }

    // ------------------------------------------------------------- devices / config

    fun refreshDevices() {
        val ins = Devices.inputs(am)
        val outs = Devices.outputs(am)
        val s = _ui.value
        val inId = s.inputId?.takeIf { id -> ins.any { it.id == id } }
            ?: ins.firstOrNull { !it.builtin }?.id ?: ins.firstOrNull()?.id
        val outId = when {
            s.outputId != null && outs.any { it.id == s.outputId } -> s.outputId
            s.outputChosen -> null // user's pick disappeared, so fall back to silence rather than the speaker
            else -> outs.firstOrNull { !it.builtin }?.id
        }
        val lost = s.running &&
            (ins.none { it.id == s.inputId } || (s.outputId != null && outs.none { it.id == s.outputId }))
        _ui.update { it.copy(inputs = ins, outputs = outs, inputId = inId, outputId = outId) }
        if (lost) {
            stopEngine()
            message("Device disconnected, engine stopped")
        }
    }

    fun selectInput(id: Int) = reconfigure { it.copy(inputId = id) }
    fun selectOutput(id: Int?) = reconfigure { it.copy(outputId = id, outputChosen = true) }
    fun selectPreset(p: InputPreset) = reconfigure {
        // New preset = new platform-effect defaults; drop explicit overrides.
        it.copy(preset = p, dsp = it.dsp.copy(aec = null, ns = null, agc = null))
    }
    fun selectSampleRate(sr: Int) = reconfigure { it.copy(sampleRate = sr) }
    fun setLowLatency(on: Boolean) = reconfigure { it.copy(lowLatency = on) }

    private fun reconfigure(f: (UiState) -> UiState) {
        if (_ui.value.recording) return
        val wasRunning = _ui.value.running
        if (wasRunning) stopEngine()
        _ui.update(f)
        if (wasRunning) startEngine()
    }

    // ------------------------------------------------------------- engine

    fun startEngine() {
        val s = _ui.value
        val inId = s.inputId ?: return message("No input device")
        val outId = if (s.feedbackBlocked) null else s.outputId
        try {
            engine.monitorEnabled = s.monitor
            engine.dsp = s.dsp
            engine.start(EngineConfig(inId, outId, s.preset, s.sampleRate, s.lowLatency))
            _ui.update { it.copy(running = true) }
        } catch (t: Throwable) {
            engine.stop()
            message("Start failed: ${t.message}")
        }
    }

    fun stopEngine() {
        val wasRecording = _ui.value.recording
        engine.stop()
        _ui.update { it.copy(running = false, recording = false) }
        if (wasRecording) refreshRecordings()
    }

    fun setMonitor(on: Boolean) {
        _ui.update { it.copy(monitor = on) }
        engine.monitorEnabled = on
    }

    fun updateDsp(f: (DspParams) -> DspParams) {
        val p = f(_ui.value.dsp)
        _ui.update { it.copy(dsp = p) }
        engine.dsp = p
    }

    fun resetClip() = engine.resetClip()

    fun toggleRecording() {
        if (_ui.value.recording) {
            engine.stopRecording()
            _ui.update { it.copy(recording = false) }
            refreshRecordings()
            return
        }
        if (!_ui.value.running) startEngine()
        val s = _ui.value
        if (!s.running) return
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val inSlug = (s.input?.label ?: "in").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
        val base = "${ts}_${inSlug}_${s.preset.name.lowercase()}_${s.sampleRate / 1000}k"
        try {
            engine.startRecording(repo.newFile(base), buildComment(s))
            _ui.update { it.copy(recording = true) }
        } catch (t: Throwable) {
            message("Record failed: ${t.message}")
        }
    }

    /** Settings snapshot embedded in the WAV (LIST/INFO/ICMT) for A/B comparisons later. */
    private fun buildComment(s: UiState): String {
        val st = engine.stats.value
        val d = s.dsp
        val fx = st.effects
        return listOf(
            "WavRouter",
            "in=${s.input?.label}",
            "routedIn=${st.routedIn}",
            "mics=${st.micInfo}",
            "out=${if (s.feedbackBlocked) "blocked" else s.output?.label ?: "none"}",
            "preset=${s.preset.name}",
            "sr=${s.sampleRate}",
            "lowLatency=${s.lowLatency}",
            "tap=${d.recordTap}",
            "gainDb=${d.gainDb}",
            "hpf=${d.hpf}",
            "eq=${if (d.eqOn) "${d.eqLowDb}/${d.eqMidDb}/${d.eqHighDb}" else "off"}",
            "aec=${if (fx.aecAvail) fx.aecOn else "n/a"}",
            "ns=${if (fx.nsAvail) fx.nsOn else "n/a"}",
            "agc=${if (fx.agcAvail) fx.agcOn else "n/a"}",
        ).joinToString(" | ")
    }

    // ------------------------------------------------------------- recordings

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) { _recordings.value = repo.list() }
    }

    fun play(item: RecordingItem) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(item.file.path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            _playing.value = item.file.path
        } catch (t: Throwable) {
            stopPlayback()
            message("Playback failed: ${t.message}")
        }
    }

    fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _playing.value = null
    }

    fun rename(item: RecordingItem, newName: String) {
        if (_playing.value == item.file.path) stopPlayback()
        try {
            repo.rename(item, newName)
        } catch (t: Throwable) {
            message("Rename failed: ${t.message}")
        }
        refreshRecordings()
    }

    fun delete(item: RecordingItem) {
        if (_playing.value == item.file.path) stopPlayback()
        if (!repo.delete(item)) message("Delete failed")
        refreshRecordings()
    }

    fun shareIntent(item: RecordingItem) = repo.shareIntent(item)

    // ------------------------------------------------------------- misc

    fun message(text: String) = _ui.update { it.copy(message = text) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        engine.stop()
        stopPlayback()
    }
}
