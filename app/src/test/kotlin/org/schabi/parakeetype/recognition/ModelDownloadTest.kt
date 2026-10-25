package org.schabi.parakeetype.recognition

import android.speech.ModelDownloadListener
import android.speech.SpeechRecognizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * [reportModelDownload] answers `SpeechRecognizer.triggerModelDownload(intent, executor,
 * listener)`. Parakeetype has no network access and never downloads: the model is imported in
 * the app. So the listener hears either that the model is already there or that it is
 * unavailable, never the platform's ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS.
 */
class ModelDownloadTest {

    private class RecordingListener : ModelDownloadListener {
        val events = mutableListOf<String>()
        override fun onProgress(completedPercent: Int) {
            events += "progress $completedPercent"
        }

        override fun onSuccess() {
            events += "success"
        }

        override fun onScheduled() {
            events += "scheduled"
        }

        override fun onError(error: Int) {
            events += "error $error"
        }
    }

    @Test
    fun `given an installed model, when a download is triggered, then success is reported`() {
        val listener = RecordingListener()

        reportModelDownload(modelInstalled = true, listener = listener)

        assertThat(listener.events).containsExactly("success")
    }

    @Test
    fun `given no installed model, when a download is triggered, then the language is reported unavailable`() {
        val listener = RecordingListener()

        reportModelDownload(modelInstalled = false, listener = listener)

        assertThat(listener.events).containsExactly("error ${SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE}")
    }
}
