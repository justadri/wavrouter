package com.justadri.wavrouter.audio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** 16-bit PCM WAV writer. Optional comment goes in a LIST/INFO/ICMT chunk (ffprobe/sox show it). */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    comment: String?,
) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val headerSize: Int
    private val dataSizeOffset: Int
    private var dataBytes = 0L

    init {
        val bos = ByteArrayOutputStream()
        fun str(s: String) = bos.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Long) { for (i in 0 until 4) bos.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun u16(v: Int) { bos.write(v and 0xFF); bos.write((v shr 8) and 0xFF) }

        str("RIFF"); u32(0); str("WAVE")
        str("fmt "); u32(16); u16(1); u16(channels)
        u32(sampleRate.toLong()); u32(sampleRate.toLong() * channels * 2); u16(channels * 2); u16(16)
        if (!comment.isNullOrEmpty()) {
            val txt = comment.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val pad = txt.size % 2
            str("LIST"); u32((4 + 8 + txt.size + pad).toLong()); str("INFO")
            str("ICMT"); u32(txt.size.toLong()); bos.write(txt); if (pad == 1) bos.write(0)
        }
        str("data"); dataSizeOffset = bos.size(); u32(0)
        val header = bos.toByteArray()
        headerSize = header.size
        out.write(header)
    }

    fun write(samples: ShortArray) {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples)
        out.write(bb.array())
        dataBytes += samples.size * 2
    }

    override fun close() {
        out.flush()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4); raf.write(le32(headerSize - 8 + dataBytes))
            raf.seek(dataSizeOffset.toLong()); raf.write(le32(dataBytes))
        }
    }

    private fun le32(v: Long) = ByteArray(4) { i -> ((v shr (8 * i)) and 0xFF).toByte() }
}

/** Hands audio from the audio thread to a writer thread so disk I/O never stalls capture. */
class WavSink(val file: File, val sampleRate: Int, comment: String) {
    private val queue = ArrayBlockingQueue<ShortArray>(2048)
    private val writer = WavWriter(file, sampleRate, 1, comment)

    @Volatile var droppedChunks = 0
        private set
    @Volatile var frames = 0L
        private set
    val seconds: Double get() = frames.toDouble() / sampleRate

    private val thread = Thread({
        try {
            while (true) {
                val b = queue.take()
                if (b === EOF) break
                writer.write(b)
            }
        } catch (e: Exception) {
            Log.e("WavSink", "writer failed", e)
        } finally {
            runCatching { writer.close() }
        }
    }, "wavrouter-wav").apply { start() }

    /** Audio thread only. */
    fun offer(buf: ShortArray, n: Int) {
        if (queue.offer(buf.copyOf(n))) frames += n else droppedChunks++
    }

    fun close() {
        queue.offer(EOF, 2, TimeUnit.SECONDS)
        thread.join(5000)
    }

    private companion object {
        val EOF = ShortArray(0)
    }
}

data class WavInfo(val sampleRate: Int, val channels: Int, val durationSec: Double, val comment: String?)

object WavInfoReader {
    fun read(file: File): WavInfo? {
        return try {
            RandomAccessFile(file, "r").use { raf -> parse(raf) }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(raf: RandomAccessFile): WavInfo? {
        val len = raf.length()
        fun id(): String { val b = ByteArray(4); raf.readFully(b); return String(b, Charsets.US_ASCII) }
        fun u32(): Long {
            val b = ByteArray(4); raf.readFully(b)
            return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        fun u16(): Int { val lo = raf.read(); val hi = raf.read(); return lo or (hi shl 8) }

        if (id() != "RIFF") return null
        u32()
        if (id() != "WAVE") return null
        var sr = 0; var ch = 1; var bits = 16; var comment: String? = null; var dataBytes = -1L
        while (raf.filePointer + 8 <= len) {
            val cid = id(); val size = u32(); val start = raf.filePointer
            when (cid) {
                "fmt " -> { u16(); ch = u16(); sr = u32().toInt(); u32(); u16(); bits = u16() }
                "LIST" -> if (size >= 4 && id() == "INFO") {
                    while (raf.filePointer + 8 <= start + size) {
                        val sid = id(); val ssz = u32(); val sstart = raf.filePointer
                        if (sid == "ICMT" && ssz in 1..65536) {
                            val b = ByteArray(ssz.toInt()); raf.readFully(b)
                            comment = String(b, Charsets.UTF_8).trimEnd('\u0000')
                        }
                        raf.seek(sstart + ssz + (ssz and 1))
                    }
                }
                "data" -> {
                    // Unfinalized file (app killed mid-recording): size is 0, use what's on disk.
                    dataBytes = if (size == 0L || start + size > len) len - start else size
                    break
                }
            }
            raf.seek(start + size + (size and 1))
        }
        if (sr <= 0 || dataBytes < 0 || bits <= 0) return null
        return WavInfo(sr, ch, dataBytes.toDouble() / (sr.toLong() * ch * (bits / 8)), comment)
    }
}
