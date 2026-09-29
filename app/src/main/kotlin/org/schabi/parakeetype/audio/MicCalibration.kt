package org.schabi.parakeetype.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import org.schabi.parakeetype.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "MicCalibration"

/** 16 kHz mono — matches the ASR input format and [AudioCaptureManager]. */
private const val SAMPLE_RATE = 16_000
private const val CHUNK_SAMPLES = 480

/**
 * A single microphone input device, identified for both UI display and persistence.
 *
 * [id] is the [AudioDeviceInfo.id] used with `AudioRecord.setPreferredDevice` and persisted
 * as the user's preferred mic. [type] is the [AudioDeviceInfo] type constant (e.g.
 * [AudioDeviceInfo.TYPE_BUILTIN_MIC]). [label] is a human-readable name for the UI.
 */
data class MicInfo(
    val id: Int,
    val type: Int,
    val label: String,
)

/**
 * Quality score for a microphone's capture of a reference utterance.
 *
 * [hfFraction] is the fraction of total signal energy above [HF_CUTOFF_HZ] — the direct proxy
 * for the high-frequency fricative content the Parakeet TDT model needs to resolve words like
 * "Thanks". [snrDb] is a percentile-based signal-to-noise estimate. [score] is the composite
 * ranking value (higher is better); [hfFraction] dominates, [snrDb] breaks ties.
 */
data class MicScore(
    val hfFraction: Float,
    val snrDb: Float,
    val score: Float,
)

/**
 * Pure-Kotlin microphone quality scorer (no Android dependency — unit-testable on the JVM).
 *
 * Ranks mics with a generic fidelity proxy: the fraction of signal energy preserved above
 * [HF_CUTOFF_HZ] (high-frequency fricative content that mics attenuate to varying degrees)
 * plus a percentile-based noise margin. A higher score means a cleaner capture, which helps
 * the VAD trigger and keeps real-world decoding robust. The ASR decoder itself is
 * level-invariant (the nemo128 frontend normalises each window), so the score is not a
 * decoder requirement.
 */
object MicScorer {

    /** High-frequency band floor: the /ks/ fricative of "Thanks" sits at ~5–8 kHz. */
    const val HF_CUTOFF_HZ = 3000f

    /** Frame size (ms) for the percentile-based SNR estimate. */
    private const val SNR_FRAME_MS = 20

    /**
     * Scores [samples] (16-bit PCM, [sampleRate] Hz) for microphone quality.
     *
     * Returns a [MicScore] with a zero [score] when the clip is too short to score reliably.
     */
    fun score(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): MicScore {
        if (samples.size < sampleRate / 4) return MicScore(0f, 0f, 0f)

        // 1. High-pass at HF_CUTOFF_HZ to isolate the fricative band.
        val hp = highPass(samples, sampleRate, HF_CUTOFF_HZ)

        // 2. HF fraction: energy above the cutoff / total energy.
        var total = 0.0
        var hf = 0.0
        for (i in samples.indices) {
            val s = samples[i].toDouble()
            total += s * s
            val h = hp[i].toDouble()
            hf += h * h
        }
        val hfFraction = if (total > 1e-9) (hf / total).toFloat().coerceIn(0f, 1f) else 0f

        // 3. Percentile-based SNR: 90th-percentile frame RMS (signal) vs 10th (noise floor).
        val snrDb = estimateSnrDb(samples, sampleRate)

        // 4. Composite: HF fraction dominates (0–100), SNR adds a small tiebreak bonus.
        val score = hfFraction * 100f + max(0f, snrDb) * 0.5f
        return MicScore(hfFraction, snrDb, score)
    }

    /**
     * Estimates the signal-to-noise ratio in dB from frame-level RMS percentiles.
     * The 90th-percentile frame RMS approximates the speech level; the 10th approximates the
     * noise floor. Returns 0.0 when the noise floor is negligible (avoids log(0)).
     */
    private fun estimateSnrDb(samples: ShortArray, sampleRate: Int): Float {
        val frameSize = sampleRate * SNR_FRAME_MS / 1000
        if (samples.size < frameSize * 4) return 0f
        val frameRms = ArrayList<Float>((samples.size / frameSize) + 1)
        var i = 0
        while (i + frameSize <= samples.size) {
            var sum = 0.0
            for (j in i until i + frameSize) {
                val v = samples[j].toDouble()
                sum += v * v
            }
            frameRms.add((sqrt(sum / frameSize) / 32768.0).toFloat())
            i += frameSize
        }
        if (frameRms.size < 4) return 0f
        frameRms.sort()
        val noise = frameRms[(frameRms.size * 0.1).toInt().coerceIn(0, frameRms.size - 1)]
        val signal = frameRms[(frameRms.size * 0.9).toInt().coerceIn(0, frameRms.size - 1)]
        if (noise < 1e-5f || signal <= noise) return 0f
        return (20f * log10(signal / noise)).coerceAtMost(60f)
    }

    /** 1-pole high-pass filter at [hz] (removes low-frequency rumble / room tone). */
    private fun highPass(s: ShortArray, sr: Int, hz: Float): ShortArray {
        val rc = 1.0 / (2.0 * Math.PI * hz)
        val dt = 1.0 / sr
        val alpha = dt / (rc + dt)
        var prevIn = 0.0
        var prevOut = 0.0
        val out = ShortArray(s.size)
        for (i in s.indices) {
            val inF = s[i].toDouble()
            val outF = alpha * (prevOut + inF - prevIn)
            out[i] = outF.toInt().coerceIn(-32768, 32767).toShort()
            prevIn = inF
            prevOut = outF
        }
        return out
    }
}

/**
 * Enumerates the device's input microphones and records a short reference clip on a chosen
 * mic, so the calibration can pick the highest-fidelity one and [AudioCaptureManager] can
 * apply it via `AudioRecord.setPreferredDevice`.
 *
 * All capture runs on [Dispatchers.IO]. Requires [android.Manifest.permission.RECORD_AUDIO].
 */
class MicCalibrationManager(private val context: Context) {

    /**
     * Lists the input microphone devices available to the [MediaRecorder.AudioSource.DEFAULT]
     * source. Returns an empty list when the platform exposes none (rare; most devices have at
     * least the builtin mic).
     */
    fun listInputMics(): List<MicInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val mics = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        // Fall back to all input devices when the platform exposes no TYPE_BUILTIN_MIC
        // (rare; most devices have at least the builtin mic).
        val source = if (mics.isEmpty()) devices.asList() else mics
        return source.map { it.toMicInfo() }.withUniqueLabels()
    }

    /** Resolves the [AudioDeviceInfo] for a persisted [MicInfo.id], or null if it's gone. */
    fun deviceForId(id: Int): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    /**
     * Records [durationMs] of audio on [mic] and returns the raw 16-bit PCM samples.
     *
     * @throws SecurityException if [android.Manifest.permission.RECORD_AUDIO] is not granted.
     * @throws IllegalStateException if the [AudioRecord] fails to initialise.
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun recordOnMic(mic: MicInfo, durationMs: Int): ShortArray = withContext(Dispatchers.IO) {
        if (!PermissionHelper.hasRecordPermission(context)) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, CHUNK_SAMPLES * 2 * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise (state=${recorder.state})"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val device = deviceForId(mic.id)
            if (device != null) {
                recorder.setPreferredDevice(device)
                Log.d(TAG, "Recording on mic id=${mic.id} (${mic.label})")
            } else {
                Log.w(TAG, "Preferred mic id=${mic.id} not found — using default device")
            }
        }

        val totalSamples = SAMPLE_RATE * durationMs / 1000
        val data = ShortArray(totalSamples)
        val buf = ShortArray(CHUNK_SAMPLES)
        var offset = 0
        recorder.startRecording()
        try {
            while (offset < totalSamples) {
                val toRead = minOf(buf.size, totalSamples - offset)
                val read = recorder.read(buf, 0, toRead)
                if (read > 0) {
                    buf.copyInto(data, offset, 0, read)
                    offset += read
                } else if (read < 0) {
                    Log.w(TAG, "recordOnMic read error: $read")
                    break
                }
            }
        } finally {
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "recordOnMic stop failed", e)
            }
            recorder.release()
        }
        if (offset < totalSamples) data.copyOf(offset) else data
    }

    private fun AudioDeviceInfo.toMicInfo() = MicInfo(
        id = id,
        type = type,
        label = if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            builtinMicLabel()
        } else {
            productName?.takeIf { it.isNotBlank() }?.toString() ?: defaultLabelFor(type)
        },
    )

    private fun defaultLabelFor(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> context.getString(R.string.mic_builtin)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.mic_wired_headset)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> context.getString(R.string.mic_type_format, type)
    }

    /**
     * Describes a built-in microphone for the UI.
     *
     * [AudioDeviceInfo.getProductName] returns the HAL board name (e.g. "V2454A"), which is
     * identical for every built-in mic on a device, so it can't distinguish them. Instead we
     * use what the platform reliably exposes:
     *  - the channel layout ([AudioDeviceInfo.getChannelMasks]) — a single mono capsule vs. a
     *    stereo or front/back mic array, and
     *  - the position ([AudioDeviceInfo.getAddress]) when the HAL names it (the framework maps
     *    address "back" to the back mic).
     */
    private fun AudioDeviceInfo.builtinMicLabel(): String {
        val parts = mutableListOf<String>()
        address
            .takeIf { it.isNotBlank() && it.length <= 12 && it.all(Char::isLetter) }
            ?.let { parts.add(it.lowercase()) }
        when {
            channelMasks.any { it and AudioFormat.CHANNEL_IN_BACK != 0 } -> parts.add(context.getString(R.string.mic_front_back))
            channelMasks.any { it and AudioFormat.CHANNEL_IN_STEREO == AudioFormat.CHANNEL_IN_STEREO } -> parts.add(context.getString(R.string.mic_stereo))
            else -> parts.add(context.getString(R.string.mic_mono))
        }
        return context.getString(R.string.mic_builtin_format, parts.joinToString(", "))
    }

    /**
     * Guarantees every mic has a distinct [MicInfo.label] for the UI. When two mics describe
     * themselves identically (common for built-in mics that share a board name and channel
     * layout), the later ones get a numeric suffix.
     */
    private fun List<MicInfo>.withUniqueLabels(): List<MicInfo> {
        val seen = mutableMapOf<String, Int>()
        return map { mic ->
            val n = seen.getOrPut(mic.label) { 0 } + 1
            seen[mic.label] = n
            if (n == 1) mic else mic.copy(label = "${mic.label} $n")
        }
    }
}
