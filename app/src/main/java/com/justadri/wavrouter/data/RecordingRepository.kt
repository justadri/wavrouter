package com.justadri.wavrouter.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.justadri.wavrouter.audio.WavInfo
import com.justadri.wavrouter.audio.WavInfoReader
import java.io.File

data class RecordingItem(val file: File, val sizeBytes: Long, val modified: Long, val info: WavInfo?)

/**
 * Recordings live in the app's external files dir so you can grab them with
 * `adb pull /sdcard/Android/data/com.justadri.wavrouter/files/Music/`.
 */
class RecordingRepository(private val context: Context) {
    val dir: File = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music"))
        .apply { mkdirs() }

    fun list(): List<RecordingItem> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { RecordingItem(it, it.length(), it.lastModified(), WavInfoReader.read(it)) }

    fun newFile(base: String): File {
        var f = File(dir, "$base.wav")
        var i = 2
        while (f.exists()) f = File(dir, "${base}_${i++}.wav")
        return f
    }

    fun rename(item: RecordingItem, newName: String): File {
        val clean = newName.trim().removeSuffix(".wav").replace(Regex("""[/\\:*?"<>|]"""), "_")
        require(clean.isNotBlank()) { "Name is empty" }
        val target = File(dir, "$clean.wav")
        if (target == item.file) return target
        require(!target.exists()) { "${target.name} already exists" }
        check(item.file.renameTo(target)) { "Rename failed" }
        return target
    }

    fun delete(item: RecordingItem): Boolean = item.file.delete()

    fun shareIntent(item: RecordingItem): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", item.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, item.file.name)
    }
}
