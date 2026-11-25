package com.justadri.wavrouter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justadri.wavrouter.audio.MeterReading
import kotlin.math.roundToInt

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun <T> Picker(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
                }
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    sub: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun DbSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp))
        Slider(
            value = value,
            onValueChange = { onChange((it * 2).roundToInt() / 2f) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text("%+.1f dB".format(value), Modifier.width(76.dp), fontFamily = FontFamily.Monospace)
    }
}

private const val METER_MIN_DB = -72f

@Composable
fun Meter(title: String, m: MeterReading, onResetClip: () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ok = Color(0xFF43A047)
    val hot = Color(0xFFFDD835)
    val bad = MaterialTheme.colorScheme.error
    val peakColor = MaterialTheme.colorScheme.onSurface
    fun frac(db: Float) = ((db - METER_MIN_DB) / -METER_MIN_DB).coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            if (m.clipped) {
                Text(
                    "CLIP (tap to reset)",
                    color = bad,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onResetClip),
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawRect(track)
            val color = when {
                m.peakDb > -1f -> bad
                m.peakDb > -9f -> hot
                else -> ok
            }
            drawRect(color, size = Size(size.width * frac(m.rmsDb), size.height))
            val px = size.width * frac(m.peakDb)
            drawRect(peakColor, topLeft = Offset((px - 1.5f).coerceAtLeast(0f), 0f), size = Size(3f, size.height))
            val fx = size.width * frac(m.floorDb)
            drawRect(peakColor.copy(alpha = 0.4f), topLeft = Offset((fx - 1f).coerceAtLeast(0f), 0f), size = Size(2f, size.height))
        }
        Text(
            "peak %6.1f  rms %6.1f  floor %6.1f dBFS".format(m.peakDb, m.rmsDb, m.floorDb),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
