package org.schabi.parakeetype.ui.keyboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.parakeetype.audio.AudioChunk

/**
 * [ImmediateEndpointer] ends an immediate-mode session after the end of speech (the VAD's
 * silence boundary plus the remaining silence) or when nothing is said at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateEndpointerTest {

    private val speech = AudioChunk(ShortArray(480) { 1000 })
    private val boundary = AudioChunk(ShortArray(0), isSilenceBoundary = true)

    private fun TestScope.endpointer(onEnd: () -> Unit) = ImmediateEndpointer(backgroundScope, onEnd)

    @Test
    fun `ends when nothing is said`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()

        advanceTimeBy(IMMEDIATE_NO_SPEECH_TIMEOUT_MS - 1)
        assertThat(ended).isZero()
        advanceTimeBy(2)
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun `speech cancels the no-speech timeout`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.onChunk(speech)

        advanceTimeBy(IMMEDIATE_NO_SPEECH_TIMEOUT_MS * 2)
        assertThat(ended).isZero()
    }

    @Test
    fun `ends after the silence following speech`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.onChunk(speech)
        endpointer.onChunk(boundary)

        runCurrent()
        assertThat(ended).isZero()
        advanceTimeBy(IMMEDIATE_END_OF_SPEECH_SILENCE_MS)
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun `resumed speech keeps the session going`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.onChunk(speech)
        endpointer.onChunk(boundary)
        advanceTimeBy(100)
        endpointer.onChunk(speech)

        advanceTimeBy(IMMEDIATE_END_OF_SPEECH_SILENCE_MS * 2)
        assertThat(ended).isZero()

        endpointer.onChunk(boundary)
        advanceTimeBy(IMMEDIATE_END_OF_SPEECH_SILENCE_MS)
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun `a boundary before any speech does not end the session early`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.onChunk(boundary)

        advanceTimeBy(IMMEDIATE_END_OF_SPEECH_SILENCE_MS * 2)
        assertThat(ended).isZero()
    }

    @Test
    fun `cancel stops both timers`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.cancel()
        advanceTimeBy(IMMEDIATE_NO_SPEECH_TIMEOUT_MS * 2)

        endpointer.start()
        endpointer.onChunk(speech)
        endpointer.onChunk(boundary)
        endpointer.cancel()
        endpointer.onChunk(speech)
        endpointer.onChunk(boundary)
        advanceTimeBy(IMMEDIATE_NO_SPEECH_TIMEOUT_MS * 2)

        assertThat(ended).isZero()
    }

    @Test
    fun `ends only once`() = runTest {
        var ended = 0
        val endpointer = endpointer { ended++ }
        endpointer.start()
        endpointer.onChunk(speech)
        endpointer.onChunk(boundary)
        advanceTimeBy(IMMEDIATE_END_OF_SPEECH_SILENCE_MS)
        // Hangover drained after the stop.
        endpointer.onChunk(boundary)
        advanceTimeBy(IMMEDIATE_NO_SPEECH_TIMEOUT_MS * 2)

        assertThat(ended).isEqualTo(1)
    }
}
