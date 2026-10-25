package org.schabi.parakeetype.inference

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Creates an engine with [create], loads [modelDir] into it and applies [configure].
 *
 * The engine is returned only once it is fully set up. When loading or configuring fails, or
 * the calling coroutine is cancelled meanwhile, the engine is closed before the exception
 * propagates: a loaded Parakeet engine holds hundreds of MB of native memory, and
 * [SpeechEngine.load] blocks without reacting to cancellation, so an [InferenceService]
 * destroyed during a load would otherwise leak the finished engine.
 */
internal suspend fun loadEngine(
    create: () -> SpeechEngine,
    modelDir: File,
    configure: suspend (SpeechEngine) -> Unit = {},
): SpeechEngine {
    val engine = create()
    try {
        engine.load(modelDir)
        configure(engine)
        currentCoroutineContext().ensureActive()
        return engine
    } catch (e: Throwable) {
        engine.close()
        throw e
    }
}
