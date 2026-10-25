package org.schabi.parakeetype.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.parakeetype.audio.AudioChunk
import java.io.File

/**
 * [loadEngine] hands out an engine only once it is fully set up. Whenever it does not hand it
 * out — loading or configuring failed, or the caller was cancelled meanwhile — it closes the
 * engine: a loaded Parakeet engine holds ~660 MB of native memory, and an [InferenceService]
 * destroyed during the (blocking, uncancellable) load used to leak it, so the next load
 * doubled the process and the low-memory killer took it down mid-session.
 */
class EngineLoaderTest {

    private class RecordingEngine(private val onLoad: () -> Unit = {}) : SpeechEngine {
        var loadedFrom: File? = null
        var closed = false
        override val isLoaded get() = loadedFrom != null && !closed
        override fun load(modelDir: File) {
            loadedFrom = modelDir
            onLoad()
        }

        override fun transcribe(chunk: AudioChunk): TranscriptResult = TranscriptResult.NoSpeech
        override fun close() {
            closed = true
        }
    }

    private val modelDir = File("models/parakeet-ultra")

    @Test
    fun `given a successful load, when loading, then the configured engine is returned open`() = runTest {
        val engine = RecordingEngine()
        var configured = false

        val loaded = loadEngine(create = { engine }, modelDir = modelDir, configure = { configured = true })

        assertThat(loaded).isSameAs(engine)
        assertThat(engine.loadedFrom).isEqualTo(modelDir)
        assertThat(configured).isTrue()
        assertThat(engine.closed).isFalse()
    }

    @Test
    fun `given a failing load, when loading, then the engine is closed and the error propagates`() = runTest {
        val engine = RecordingEngine(onLoad = { throw IllegalStateException("model broken") })

        val result = runCatching { loadEngine(create = { engine }, modelDir = modelDir) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(engine.closed).isTrue()
    }

    @Test
    fun `given the caller is cancelled while configuring, when loading, then the engine is closed`() = runTest {
        val engine = RecordingEngine()

        val job = launch {
            loadEngine(create = { engine }, modelDir = modelDir, configure = { throw CancellationException("destroyed") })
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(engine.closed).isTrue()
    }

    @Test
    fun `given the caller is cancelled during the blocking load, when loading, then the engine is closed`() = runTest {
        lateinit var job: Job
        val engine = RecordingEngine(onLoad = { job.cancel() })

        job = launch { loadEngine(create = { engine }, modelDir = modelDir) }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(engine.closed).isTrue()
    }
}
