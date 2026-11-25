package com.justadri.wavrouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justadri.wavrouter.MainViewModel
import com.justadri.wavrouter.data.RecordingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(vm: MainViewModel) {
    val items by vm.recordings.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var renameTarget by remember { mutableStateOf<RecordingItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.recordingsDir,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::refreshRecordings) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (items.isEmpty()) {
            Text("No recordings yet.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.file.path }) { item ->
                RecordingRow(
                    item = item,
                    isPlaying = playing == item.file.path,
                    onPlay = { if (playing == item.file.path) vm.stopPlayback() else vm.play(item) },
                    onRename = { renameTarget = item },
                    onShare = { ctx.startActivity(vm.shareIntent(item)) },
                    onDelete = { deleteTarget = item },
                )
            }
        }
    }

    renameTarget?.let { item ->
        var name by remember(item) { mutableStateOf(item.file.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, suffix = { Text(".wav") }) },
            confirmButton = { TextButton(onClick = { vm.rename(item, name); renameTarget = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text(item.file.name) },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text(item.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = item.info
            Text(
                buildString {
                    if (info != null) append("%.1f s · %d Hz · ".format(info.durationSec, info.sampleRate))
                    append("%.1f MB · ".format(item.sizeBytes / 1_048_576.0))
                    append(fmt.format(Date(item.modified)))
                },
                style = MaterialTheme.typography.bodySmall,
            )
            info?.comment?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Row {
                IconButton(onClick = onPlay) {
                    Icon(if (isPlaying) Icons.Filled.Close else Icons.Filled.PlayArrow, if (isPlaying) "Stop" else "Play")
                }
                IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Rename") }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
}
