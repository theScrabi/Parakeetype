package org.schabi.parakeetype

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.inference.ParakeetEngine
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.settings.model.ModelId
import org.schabi.parakeetype.settings.model.ModelRegistry
import org.schabi.parakeetype.settings.model.ModelStorageManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ParakeetModelDeviceTest"

/** Encoder frames per streaming chunk: 2 s at 80 ms per frame. */
private const val CHUNK_FRAMES = 25

/**
 * Manual on-device check of the installed Parakeet model: transcribes every clip in
 * `<filesDir>/testaudio/` (raw 16 kHz mono 16-bit little-endian PCM, pushed with `run-as`)
 * one-shot and through the chunked streaming primitives, and logs both transcripts and
 * timings under [TAG]. Skipped when the model or the clips are not installed.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetModelDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun parakeetUltra() = transcribeClips(ModelId.PARAKEET_ULTRA)

    private fun transcribeClips(modelId: ModelId) {
        assumeTrue("$modelId not installed", ModelStorageManager.isModelReady(context, ModelRegistry[modelId]))
        val clips = File(context.filesDir, "testaudio").listFiles { f -> f.extension == "pcm" }?.sorted().orEmpty()
        assumeTrue("no clips in testaudio/", clips.isNotEmpty())

        val engine = ParakeetEngine()
        val loadStart = System.currentTimeMillis()
        engine.load(ModelStorageManager.getModelDir(context, modelId))
        Log.i(TAG, "$modelId loaded in ${System.currentTimeMillis() - loadStart} ms")
        try {
            for (clip in clips) {
                val samples = readPcm(clip)
                val audioMs = samples.size / 16

                val oneShotStart = System.currentTimeMillis()
                val oneShot = when (val r = engine.transcribe(AudioChunk(samples))) {
                    is TranscriptResult.Final -> "${r.text}  (conf ${"%.2f".format(r.confidence)})"
                    is TranscriptResult.Partial -> r.text
                    else -> r.toString()
                }
                val oneShotMs = System.currentTimeMillis() - oneShotStart

                val streamStart = System.currentTimeMillis()
                val streamed = decodeInChunks(engine, samples)
                val streamMs = System.currentTimeMillis() - streamStart

                Log.i(TAG, "$modelId ${clip.nameWithoutExtension} (${audioMs} ms audio)")
                Log.i(TAG, "  one-shot [${oneShotMs} ms]: $oneShot")
                Log.i(TAG, "  chunked  [${streamMs} ms]: $streamed")
            }
        } finally {
            engine.close()
        }
    }

    /** Encodes the whole clip once and decodes it in [CHUNK_FRAMES] ranges with the TDT state carried over. */
    private fun decodeInChunks(engine: ParakeetEngine, samples: ShortArray): String {
        val (encoded, length) = engine.encodeBuffer(FloatArray(samples.size) { samples[it] / 32_768f })
        return encoded.use {
            var state = engine.initialTdtState()
            val tokens = mutableListOf<Int>()
            var start = 0
            while (start < length) {
                val end = minOf(start + CHUNK_FRAMES, length)
                val result = engine.decodeChunk(encoded, length, start + state.frameDelta, end, state)
                tokens += result.tokens
                state = result.state
                start = end
            }
            engine.detokenizeTokens(tokens)
        }
    }

    private fun readPcm(file: File): ShortArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }
}
