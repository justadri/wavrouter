package com.justadri.wavrouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justadri.wavrouter.MainViewModel
import com.justadri.wavrouter.UiState
import com.justadri.wavrouter.audio.DeviceOption
import com.justadri.wavrouter.audio.InputPreset
import com.justadri.wavrouter.audio.RecordTap

private val SAMPLE_RATES = listOf(16000, 24000, 44100, 48000)

@Composable
fun MonitorScreen(vm: MainViewModel, ui: UiState) {
    val stats by vm.engine.stats.collectAsStateWithLifecycle()
    val inMeter by vm.engine.inputMeter.collectAsStateWithLifecycle()
    val outMeter by vm.engine.outputMeter.collectAsStateWithLifecycle()
    val locked = ui.recording // routing changes restart the engine, so not mid-recording

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------------------------------------------------------- transport
        Section("Engine") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (ui.running) vm.stopEngine() else vm.startEngine() }) {
                    Text(if (ui.running) "Stop" else "Start")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = vm::toggleRecording,
                    colors = if (ui.recording) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) else ButtonDefaults.buttonColors(),
                ) { Text(if (ui.recording) "■ Stop rec" else "● Record") }
                Spacer(Modifier.weight(1f))
                if (ui.recording) {
                    Text("%.1f s".format(stats.recSeconds), fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Record:", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = ui.dsp.recordTap == RecordTap.RAW,
                    onClick = { vm.updateDsp { it.copy(recordTap = RecordTap.RAW) } },
                    label = { Text("Raw capture") },
                    enabled = !ui.recording,
                )
                FilterChip(
                    selected = ui.dsp.recordTap == RecordTap.PROCESSED,
                    onClick = { vm.updateDsp { it.copy(recordTap = RecordTap.PROCESSED) } },
                    label = { Text("Post-DSP") },
                    enabled = !ui.recording,
                )
            }
            SwitchRow(
                label = "Monitor to output",
                checked = ui.monitor && !ui.feedbackBlocked && ui.outputId != null,
                enabled = !ui.feedbackBlocked && ui.outputId != null,
                sub = when {
                    ui.feedbackBlocked -> "Blocked: built-in mic to built-in speaker/earpiece (feedback guard)"
                    ui.outputId == null -> "No output selected"
                    else -> null
                },
                onChange = vm::setMonitor,
            )
            if (ui.running) {
                val bufMs = if (stats.sampleRate > 0) (stats.chunkFrames + stats.outBufferFrames) * 1000 / stats.sampleRate else 0
                Text(
                    buildString {
                        appendLine("in  → ${stats.routedIn}")
                        appendLine("out → ${stats.routedOut}")
                        appendLine("${stats.sampleRate} Hz · chunk ${stats.chunkFrames} · out buf ${stats.outBufferFrames} (≈$bufMs ms)")
                        append("underruns ${stats.underruns} · out dropped ${stats.outDropped} · rec dropped ${stats.recDropped}")
                        if (stats.micInfo.isNotBlank()) append("\nmic: ${stats.micInfo}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ---------------------------------------------------------------- meters
        Section("Levels") {
            Meter("Input (raw capture)", inMeter, vm::resetClip)
            Meter("Output (post-DSP)", outMeter, vm::resetClip)
        }

        // ---------------------------------------------------------------- routing
        Section("Routing") {
            Picker(
                label = "Input",
                selectedLabel = ui.input?.label ?: "—",
                options = ui.inputs,
                optionLabel = DeviceOption::label,
                enabled = !locked,
            ) { vm.selectInput(it.id) }
            ui.input?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall) }

            Picker<DeviceOption?>(
                label = "Output",
                selectedLabel = ui.output?.label ?: "None (meter/record only)",
                options = listOf<DeviceOption?>(null) + ui.outputs,
                optionLabel = { it?.label ?: "None (meter/record only)" },
                enabled = !locked,
            ) { vm.selectOutput(it?.id) }
            ui.output?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall) }

            Picker(
                label = "Capture preset (audio source)",
                selectedLabel = presetLabel(ui.preset, ui),
                options = InputPreset.entries,
                optionLabel = { presetLabel(it, ui) },
                enabled = !locked,
            ) { vm.selectPreset(it) }

            Picker(
                label = "Sample rate",
                selectedLabel = "${ui.sampleRate} Hz",
                options = SAMPLE_RATES,
                optionLabel = { "$it Hz" },
                enabled = !locked,
            ) { vm.selectSampleRate(it) }

            SwitchRow(
                label = "Low latency",
                checked = ui.lowLatency,
                enabled = !locked,
                sub = "Fast-track output and burst-sized chunks. Lowest latency at 48 kHz.",
                onChange = vm::setLowLatency,
            )
        }

        // ---------------------------------------------------------------- processing
        Section("App DSP (monitor + post-DSP recordings)") {
            DbSlider("Gain", ui.dsp.gainDb, -24f..36f) { v -> vm.updateDsp { it.copy(gainDb = v) } }
            SwitchRow("High-pass 80 Hz", ui.dsp.hpf) { v -> vm.updateDsp { it.copy(hpf = v) } }
            SwitchRow("EQ", ui.dsp.eqOn, sub = "150 Hz shelf · 1 kHz peak · 4 kHz shelf") { v ->
                vm.updateDsp { it.copy(eqOn = v) }
            }
            DbSlider("Low", ui.dsp.eqLowDb, -12f..12f, ui.dsp.eqOn) { v -> vm.updateDsp { it.copy(eqLowDb = v) } }
            DbSlider("Mid", ui.dsp.eqMidDb, -12f..12f, ui.dsp.eqOn) { v -> vm.updateDsp { it.copy(eqMidDb = v) } }
            DbSlider("High", ui.dsp.eqHighDb, -12f..12f, ui.dsp.eqOn) { v -> vm.updateDsp { it.copy(eqHighDb = v) } }
        }

        Section("Platform effects (act on capture, so they affect raw recordings too)") {
            val fx = stats.effects
            val live = ui.running
            fun sub(avail: Boolean) = when {
                !live -> "Start the engine to see this preset's default"
                !avail -> "Not available on this device/session"
                else -> null
            }
            SwitchRow("Echo cancellation (AEC)", fx.aecOn, live && fx.aecAvail, sub(fx.aecAvail) ?: "Only meaningful with Voice communication preset") { v ->
                vm.updateDsp { it.copy(aec = v) }
            }
            SwitchRow("Noise suppression", fx.nsOn, live && fx.nsAvail, sub(fx.nsAvail)) { v -> vm.updateDsp { it.copy(ns = v) } }
            SwitchRow("Auto gain control", fx.agcOn, live && fx.agcAvail, sub(fx.agcAvail)) { v -> vm.updateDsp { it.copy(agc = v) } }
        }
    }
}

private fun presetLabel(p: InputPreset, ui: UiState): String =
    if (p == InputPreset.UNPROCESSED && !ui.unprocessedSupported) "${p.label} (not advertised)" else p.label
