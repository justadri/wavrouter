package com.justadri.wavrouter.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ-cookbook biquad, transposed direct form II. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun highPass(fs: Int, f0: Double, q: Double = 0.7071) {
        val w0 = 2 * PI * f0 / fs; val c = cos(w0); val alpha = sin(w0) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun peaking(fs: Int, f0: Double, gainDb: Float, q: Double) {
        val a = 10.0.pow(gainDb / 40.0); val w0 = 2 * PI * f0 / fs; val c = cos(w0)
        val alpha = sin(w0) / (2 * q)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(fs: Int, f0: Double, gainDb: Float, q: Double = 0.7071) {
        val a = 10.0.pow(gainDb / 40.0); val w0 = 2 * PI * f0 / fs; val c = cos(w0)
        val k = 2 * sqrt(a) * sin(w0) / (2 * q)
        set(
            a * ((a + 1) - (a - 1) * c + k),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - k),
            (a + 1) + (a - 1) * c + k,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - k,
        )
    }

    fun highShelf(fs: Int, f0: Double, gainDb: Float, q: Double = 0.7071) {
        val a = 10.0.pow(gainDb / 40.0); val w0 = 2 * PI * f0 / fs; val c = cos(w0)
        val k = 2 * sqrt(a) * sin(w0) / (2 * q)
        set(
            a * ((a + 1) + (a - 1) * c + k),
            -2 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - k),
            (a + 1) - (a - 1) * c + k,
            2 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - k,
        )
    }
}

/** Gain -> HPF(80 Hz) -> 3-band EQ (150 Hz shelf, 1 kHz peak, 4 kHz shelf). Runs on the audio thread. */
class DspChain {
    private var last: DspParams? = null
    private var lastSr = 0
    private var gain = 1f
    private val hpf = Biquad()
    private val low = Biquad()
    private val mid = Biquad()
    private val high = Biquad()

    /** Recompute coefficients only when the params object changed (DspParams is immutable). */
    fun configure(p: DspParams, sr: Int) {
        if (p === last && sr == lastSr) return
        gain = 10f.pow(p.gainDb / 20f)
        hpf.highPass(sr, 80.0)
        low.lowShelf(sr, 150.0, p.eqLowDb)
        mid.peaking(sr, 1000.0, p.eqMidDb, 1.0)
        high.highShelf(sr, minOf(4000.0, sr * 0.4), p.eqHighDb)
        last = p
        lastSr = sr
    }

    fun process(buf: FloatArray, n: Int, p: DspParams) {
        for (i in 0 until n) {
            var x = buf[i] * gain
            if (p.hpf) x = hpf.process(x)
            if (p.eqOn) {
                x = low.process(x); x = mid.process(x); x = high.process(x)
            }
            buf[i] = x
        }
    }
}

fun softClip(x: Float): Float {
    val a = abs(x)
    if (a <= 0.9f) return x
    val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
    return if (x < 0) -y else y
}

/** Emits a reading every ~50 ms. Noise floor = running minimum RMS that creeps up ~0.5 dB/s. */
class LevelMeter(sampleRate: Int) {
    private val window = maxOf(1, sampleRate / 20)
    private var peak = 0f
    private var sumSq = 0.0
    private var count = 0
    private var floorDb = 0f
    var clipped = false

    fun add(buf: FloatArray, n: Int): MeterReading? {
        for (i in 0 until n) {
            val a = abs(buf[i])
            if (a > peak) peak = a
            sumSq += (a * a).toDouble()
            if (a >= 0.999f) clipped = true
        }
        count += n
        if (count < window) return null
        val rmsDb = (10.0 * log10(sumSq / count + 1e-12)).toFloat().coerceAtLeast(-120f)
        val peakDb = (20.0 * log10(peak.toDouble() + 1e-9)).toFloat().coerceAtLeast(-120f)
        floorDb = if (rmsDb < floorDb) rmsDb else minOf(floorDb + 0.025f, rmsDb)
        peak = 0f; sumSq = 0.0; count = 0
        return MeterReading(peakDb, rmsDb, floorDb, clipped)
    }
}
