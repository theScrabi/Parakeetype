package org.schabi.parakeetype.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.inference.InferenceService
import org.schabi.parakeetype.inference.WordTiming
import org.schabi.parakeetype.recognition.InferenceConnection
import org.schabi.parakeetype.recognition.LanguageDetection
import org.schabi.parakeetype.recognition.RecognitionOptions
import org.schabi.parakeetype.recognition.RecognitionSession
import org.schabi.parakeetype.settings.SettingsActivity
import org.schabi.parakeetype.settings.preferences.AppPreferences

private const val TAG = "BubbleService"

/** Debounce for re-checking whether the bubble should show after accessibility events. */
private const val UPDATE_DELAY_MS = 120L

private const val PREFS = "bubble"
private const val PREF_X = "x"
private const val PREF_Y = "y"
private const val EDGE_MARGIN_DP = 8

/**
 * Shows a floating microphone bubble while another keyboard is open on a text field, so
 * dictation works without switching to the Parakeetype keyboard.
 *
 * Tap the bubble to start a segmented [RecognitionSession] (the same pipeline and loaded model
 * as the keyboard and the system recognizer); each finished utterance is inserted at the
 * cursor of the field that was focused when dictation started. Tap again to stop; the audio
 * captured so far is still transcribed and inserted.
 *
 * Insertion uses [AccessibilityNodeInfo.ACTION_SET_TEXT] (splicing at the selection), and
 * falls back to the clipboard plus [AccessibilityNodeInfo.ACTION_PASTE] for fields that
 * reject it. The bubble draws as a [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY],
 * so no separate "display over other apps" permission is needed. Nothing leaves the device.
 */
class BubbleAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateVisibility() }

    private lateinit var windowManager: WindowManager
    private lateinit var inference: InferenceConnection
    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var session: RecognitionSession? = null

    /** The field dictation started in; utterances go here even if focus moves meanwhile. */
    private var target: AccessibilityNodeInfo? = null

    private var keepModelLoaded = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inference = InferenceConnection(this)
        scope.launch {
            keepModelLoaded = AppPreferences(applicationContext).keepModelLoaded.first()
        }
        Log.i(TAG, "Bubble service connected")
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, UPDATE_DELAY_MS)
    }

    override fun onInterrupt() {
        cancelSession()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelSession()
        removeBubble()
        if (::inference.isInitialized) inference.unbind()
        scope.cancel()
        super.onDestroy()
    }

    // ── Visibility ────────────────────────────────────────────────────────────────────────────

    private fun updateVisibility() {
        if (session != null) return // keep the bubble while dictating
        val show = isForeignKeyboardVisible() && findEditableFocus() != null
        if (show) showBubble() else hideBubble()
    }

    /** A keyboard other than Parakeetype's own is on screen. */
    private fun isForeignKeyboardVisible(): Boolean = try {
        windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    window.root?.packageName?.toString() != packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read windows", e)
        false
    }

    /** The focused, editable, non-password field of another app, or `null`. */
    private fun findEditableFocus(): AccessibilityNodeInfo? = try {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { node ->
            node.isEditable && !node.isPassword && node.packageName?.toString() != packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not find the focused field", e)
        null
    }

    private fun showBubble() {
        if (attached) return
        val view = bubble ?: BubbleView(
            this,
            onTap = ::onBubbleTap,
            onDrag = ::moveBy,
            onDragEnd = ::snapToEdge,
        ).also { bubble = it }
        val lp = params ?: createLayoutParams().also { params = it }
        try {
            windowManager.addView(view, lp)
            attached = true
            // Start loading the model now, so it is ready (or nearly) by the time of the tap.
            inference.bind()
        } catch (e: Exception) {
            Log.e(TAG, "Could not add the bubble", e)
        }
    }

    private fun hideBubble() {
        if (!attached) return
        removeBubble()
        // Without "keep model loaded" the model is released together with the bubble.
        if (!keepModelLoaded) inference.unbind()
    }

    private fun removeBubble() {
        val view = bubble ?: return
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the bubble", e)
        }
        attached = false
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val bounds = windowManager.currentWindowMetrics.bounds
        val size = dp(BubbleView.BUBBLE_SIZE_DP)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, bounds.width() - size - dp(EDGE_MARGIN_DP))
            y = prefs.getInt(PREF_Y, (bounds.height() * 0.35f).toInt())
            clampToScreen(this)
        }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val lp = params ?: return
        lp.x += dx
        lp.y += dy
        clampToScreen(lp)
        if (attached) windowManager.updateViewLayout(bubble, lp)
    }

    /** Drops the bubble at the nearer side edge and remembers its position. */
    private fun snapToEdge() {
        val lp = params ?: return
        val width = windowManager.currentWindowMetrics.bounds.width()
        val size = dp(BubbleView.BUBBLE_SIZE_DP)
        val margin = dp(EDGE_MARGIN_DP)
        lp.x = if (lp.x + size / 2 < width / 2) margin else width - size - margin
        clampToScreen(lp)
        if (attached) windowManager.updateViewLayout(bubble, lp)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(PREF_X, lp.x)
            .putInt(PREF_Y, lp.y)
            .apply()
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val size = dp(BubbleView.BUBBLE_SIZE_DP)
        lp.x = lp.x.coerceIn(0, (bounds.width() - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (bounds.height() - size).coerceAtLeast(0))
    }

    // ── Dictation ─────────────────────────────────────────────────────────────────────────────

    private fun onBubbleTap() {
        val running = session
        if (running != null) {
            bubble?.state = BubbleState.Finishing
            running.stop()
            return
        }
        startDictation()
    }

    private fun startDictation() {
        if (!PermissionHelper.hasRecordPermission(this)) {
            toast(R.string.bubble_need_permission)
            startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val field = findEditableFocus()
        if (field == null) {
            toast(R.string.bubble_no_field)
            return
        }
        target = field
        // Give the process the microphone foreground-service state while the user is
        // interacting with the bubble (see InferenceService.keepLoadedServiceTypes).
        if (keepModelLoaded) InferenceService.startKeepLoaded(this)

        bubble?.state = BubbleState.Listening
        session = RecognitionSession(
            audioContext = this,
            inference = inference,
            options = RecognitionOptions(partialResults = false, segmented = true),
            listener = Listener(),
            scope = scope,
        ).also { it.start() }
    }

    private fun cancelSession() {
        session?.cancel()
        session = null
        target = null
        bubble?.state = BubbleState.Idle
    }

    private fun endSession() {
        session = null
        target = null
        bubble?.state = BubbleState.Idle
        updateVisibility()
    }

    private inner class Listener : RecognitionSession.Listener {
        override fun onModelLoading(loading: Boolean) {
            if (session == null) return
            bubble?.state = if (loading) BubbleState.Loading else BubbleState.Listening
        }

        override fun onReadyForSpeech() = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray) = Unit
        override fun onLanguageDetection(detection: LanguageDetection) = Unit
        override fun onPartialResult(text: String) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
            endSession()
        }

        override fun onRmsChanged(rmsdB: Float) {
            bubble?.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onSegmentResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
        }

        override fun onEndOfSegmentedSession() = endSession()

        override fun onError(error: Int, microphoneBusy: Boolean) {
            Log.w(TAG, "Recognition error $error" + if (microphoneBusy) " (microphone busy)" else "")
            when {
                microphoneBusy -> toast(R.string.status_error_mic_busy)
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> toast(R.string.voice_input_error_no_model)
                error == SpeechRecognizer.ERROR_AUDIO -> toast(R.string.voice_input_error_audio)
                else -> toast(R.string.voice_input_error_generic)
            }
            endSession()
        }
    }

    // ── Text insertion ────────────────────────────────────────────────────────────────────────

    private fun insert(transcript: String) {
        if (transcript.isBlank()) return
        val node = target?.takeIf { it.refresh() } ?: findEditableFocus()
        if (node == null) {
            copyToClipboard(transcript.trim())
            toast(R.string.bubble_copied)
            return
        }
        target = node
        if (setText(node, transcript)) return
        if (paste(node, transcript)) return
        copyToClipboard(transcript.trim())
        toast(R.string.bubble_copied)
    }

    /** Splices [transcript] in at the field's selection. `false` if the field refused. */
    private fun setText(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)) return false
        val current: CharSequence = if (node.isShowingHintText) "" else node.text ?: ""
        val splice = spliceTranscript(current, node.textSelectionStart, node.textSelectionEnd, transcript)
        val textArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, splice.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)) return false
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, splice.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, splice.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        return true
    }

    /** Pastes [transcript] at the cursor through the clipboard. `false` if the field refused. */
    private fun paste(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_PASTE)) return false
        val hasText = !node.isShowingHintText && !node.text.isNullOrEmpty()
        copyToClipboard(pasteText(hasText, transcript))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
