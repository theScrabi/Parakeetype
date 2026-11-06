package org.schabi.parakeetype.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
import org.schabi.parakeetype.ui.theme.ParakeetypeKeyboardTheme

private const val TAG = "BubbleService"

/** Debounce for re-checking the bubble after accessibility events. */
private const val UPDATE_DELAY_MS = 150L

private const val PREFS = "bubble"
private const val PREF_RIGHT = "docked_right"
private const val PREF_LIFT = "lift_px"
private const val PREF_FREE_Y = "free_y"

private const val EDGE_MARGIN_DP = 4
private const val DEFAULT_LIFT_DP = 8

/**
 * Shows a floating microphone bubble on focused text fields while another keyboard is in use,
 * so dictation works without switching to the Parakeetype keyboard.
 *
 * The bubble docks to a screen side and rides just above the keyboard (a drag changes the side
 * and the height above the keyboard). Tapping it starts a segmented [RecognitionSession] — the
 * same pipeline and loaded model as the keyboard and the system recognizer — and turns it into
 * a recording pill with a live waveform, cancel and done. Each finished utterance is inserted
 * at the cursor of the field that was focused when dictation started. A long-press hides the
 * bubble until another field is focused.
 *
 * Insertion uses [AccessibilityNodeInfo.ACTION_SET_TEXT] (splicing at the selection), and
 * falls back to the clipboard plus [AccessibilityNodeInfo.ACTION_PASTE]. The bubble is a
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], so no "display over other apps"
 * permission is needed. Nothing leaves the device.
 */
class BubbleAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateBubble() }

    private lateinit var windowManager: WindowManager
    private lateinit var inference: InferenceConnection
    private val ui = BubbleUiState()
    private val owner = OverlayOwner()
    private var view: BubbleComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var session: RecognitionSession? = null

    /** The field dictation started in; utterances go here even if focus moves meanwhile. */
    private var target: AccessibilityNodeInfo? = null

    /** The field the user hid the bubble for (long-press); it shows again on another field. */
    private var hiddenFor: AccessibilityNodeInfo? = null

    /** Top edge of the visible keyboard in screen pixels, or `null` when none is seen. */
    private var keyboardTop: Int? = null

    private var keepModelLoaded = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inference = InferenceConnection(this)
        ui.dockedRight = prefs().getBoolean(PREF_RIGHT, true)
        scope.launch {
            AppPreferences(applicationContext).keepModelLoaded.collect {
                keepModelLoaded = it
                // Turned off while the bubble is hidden: release the model now, not on next hide.
                if (!it && !attached && session == null) inference.unbind()
            }
        }
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, UPDATE_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.cancel()
        session = null
        removeBubble()
        owner.destroy()
        if (::inference.isInitialized) inference.unbind()
        scope.cancel()
        super.onDestroy()
    }

    // ── Visibility and placement ──────────────────────────────────────────────────────────────

    private fun updateBubble() {
        val keyboard = findKeyboard()
        keyboardTop = keyboard.top
        if (session != null) {
            // Dismissing the keyboard mid-dictation (Back, or navigating away) would otherwise
            // leave this segmented session recording with no silence timeout; cancel it.
            // Otherwise keep the bubble and follow the keyboard while dictating.
            if (keyboard.top == null) cancelDictation() else placeBubble()
            return
        }
        val field = findEditableFocus()
        if (field == null || field != hiddenFor) hiddenFor = null
        // A field keeps focus after Back dismisses the keyboard; only show while one is up.
        val show = field != null && keyboard.top != null && !keyboard.isOwn && hiddenFor == null
        if (show) showBubble() else hideBubble()
    }

    private class Keyboard(val top: Int?, val isOwn: Boolean)

    /** The on-screen keyboard window, if any. */
    private fun findKeyboard(): Keyboard = try {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null) {
            Keyboard(null, false)
        } else {
            val bounds = Rect().also { ime.getBoundsInScreen(it) }
            val pkg = ime.root?.packageName?.toString()
            Keyboard(
                top = bounds.top.takeIf { bounds.height() > 0 },
                isOwn = pkg == packageName,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read windows", e)
        Keyboard(null, false)
    }

    /**
     * The focused, editable, non-password field of another app, or `null`. Looks at the
     * input focus across windows first, then inside the active window.
     */
    private fun findEditableFocus(): AccessibilityNodeInfo? = try {
        val candidates = sequenceOf(
            { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
            { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
        )
        candidates.mapNotNull { it() }.firstOrNull { node ->
            node.isEditable && !node.isPassword && node.packageName?.toString() != packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not find the focused field", e)
        null
    }

    private fun showBubble() {
        if (!attached) {
            val v = view ?: BubbleComposeView(
                this,
                onDrag = ::moveBy,
                onDragEnd = ::dropBubble,
            ) {
                ParakeetypeKeyboardTheme {
                    BubbleContent(
                        state = ui,
                        onStart = ::startDictation,
                        onHide = ::hideForField,
                        onFinish = ::finishDictation,
                        onCancel = ::cancelDictation,
                    )
                }
            }.also {
                owner.attachTo(it)
                view = it
            }
            val lp = params ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).also { params = it }
            computePosition(lp)
            try {
                windowManager.addView(v, lp)
                attached = true
                // Start loading the model now, so it is ready (or nearly) by the time of the tap.
                inference.bind()
            } catch (e: Exception) {
                Log.e(TAG, "Could not add the bubble", e)
            }
        } else {
            placeBubble()
        }
    }

    private fun hideBubble() {
        if (!attached) return
        removeBubble()
        // Without "keep model loaded" the model is released together with the bubble.
        if (!keepModelLoaded) inference.unbind()
    }

    private fun removeBubble() {
        val v = view ?: return
        if (!attached) return
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the bubble", e)
        }
        attached = false
    }

    private var dragging = false

    /** Re-anchors the bubble (e.g. the keyboard moved), unless the user is dragging it. */
    private fun placeBubble() {
        val lp = params ?: return
        if (!attached || dragging) return
        val oldX = lp.x; val oldY = lp.y; val oldGravity = lp.gravity
        computePosition(lp)
        if (lp.x != oldX || lp.y != oldY || lp.gravity != oldGravity) windowManager.updateViewLayout(view, lp)
    }

    /** Docked side → gravity; height → just above the keyboard, or the remembered free height. */
    private fun computePosition(lp: WindowManager.LayoutParams) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val windowSize = windowSizePx()
        // Physical sides: START/END would flip in RTL locales and break the drag maths.
        lp.gravity = Gravity.TOP or if (ui.dockedRight) Gravity.RIGHT else Gravity.LEFT
        lp.x = dp(EDGE_MARGIN_DP)
        val kbTop = keyboardTop
        lp.y = if (kbTop != null) {
            kbTop - windowSize - prefs().getInt(PREF_LIFT, dp(DEFAULT_LIFT_DP))
        } else {
            prefs().getInt(PREF_FREE_Y, (bounds.height() * 0.45f).toInt())
        }
        lp.y = lp.y.coerceIn(0, (bounds.height() - windowSize).coerceAtLeast(0))
    }

    private fun moveBy(dx: Int, dy: Int) {
        val lp = params ?: return
        if (!attached) return
        if (!dragging) {
            dragging = true
            // Switch to absolute left-based coordinates for the drag.
            val width = windowManager.currentWindowMetrics.bounds.width()
            val v = view ?: return
            if (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) lp.x = width - lp.x - v.width
            lp.gravity = Gravity.TOP or Gravity.LEFT
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        lp.x = (lp.x + dx).coerceIn(0, (bounds.width() - (view?.width ?: 0)).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn(0, (bounds.height() - (view?.height ?: 0)).coerceAtLeast(0))
        windowManager.updateViewLayout(view, lp)
    }

    /** Snaps to the nearer side and remembers the side and the height above the keyboard. */
    private fun dropBubble() {
        val lp = params ?: return
        val v = view ?: return
        dragging = false
        val width = windowManager.currentWindowMetrics.bounds.width()
        ui.dockedRight = lp.x + v.width / 2 >= width / 2
        val editor = prefs().edit().putBoolean(PREF_RIGHT, ui.dockedRight)
        val kbTop = keyboardTop
        if (kbTop != null) {
            // Never over the keys: at least flush with the keyboard's top edge.
            editor.putInt(PREF_LIFT, (kbTop - windowSizePx() - lp.y).coerceAtLeast(0))
        } else {
            editor.putInt(PREF_FREE_Y, lp.y)
        }
        editor.apply()
        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        computePosition(lp)
        if (attached) windowManager.updateViewLayout(v, lp)
    }

    private fun hideForField() {
        hiddenFor = findEditableFocus()
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toast(R.string.bubble_hidden_toast)
        hideBubble()
    }

    /** Height (and idle width) of the overlay window: the bubble plus its shadow padding. */
    private fun windowSizePx(): Int = view?.height?.takeIf { it > 0 }
        ?: dp((BUBBLE_SIZE + BUBBLE_WINDOW_PADDING * 2).value.toInt())

    // ── Dictation ─────────────────────────────────────────────────────────────────────────────

    private fun startDictation() {
        if (session != null) return
        if (!PermissionHelper.hasRecordPermission(this)) {
            toast(R.string.bubble_need_permission)
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val field = findEditableFocus()
        if (field == null) {
            toast(R.string.bubble_no_field)
            return
        }
        target = field
        // Hold the microphone foreground-service state while dictating from the bubble (see
        // InferenceService.keepLoadedServiceTypes).
        if (keepModelLoaded) InferenceService.startKeepLoaded(this)

        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.resetLevels()
        ui.modelLoading = false
        ui.phase = BubblePhase.Listening
        session = RecognitionSession(
            audioContext = this,
            inference = inference,
            options = RecognitionOptions(partialResults = false, segmented = true),
            listener = Listener(),
            scope = scope,
        ).also { it.start() }
    }

    private fun finishDictation() {
        val running = session ?: return
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.phase = BubblePhase.Finishing
        running.stop()
    }

    private fun cancelDictation() {
        view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        session?.cancel()
        endSession()
    }

    private fun endSession() {
        session = null
        target = null
        ui.phase = BubblePhase.Idle
        ui.modelLoading = false
        updateBubble()
    }

    private inner class Listener : RecognitionSession.Listener {
        override fun onModelLoading(loading: Boolean) {
            ui.modelLoading = loading
        }

        override fun onReadyForSpeech() = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray) = Unit
        override fun onLanguageDetection(detection: LanguageDetection) = Unit
        override fun onPartialResult(text: String) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            ui.pushLevel((rmsdB + 2f) / 12f)
        }

        override fun onSegmentResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
        }

        override fun onResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
            endSession()
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
        // Never switch to whatever field has focus now: that could be another chat.
        val node = target?.takeIf { it.refresh() }
        if (node != null && (setText(node, transcript) || paste(node, transcript))) return
        // The field takes neither direct insertion nor paste. Copy this segment and stop the
        // session: a later segment would replace the clipboard and lose this sentence.
        copyToClipboard(transcript.trim())
        toast(R.string.bubble_copied)
        session?.cancel()
        endSession()
    }

    /**
     * The field's real text, with a placeholder read as empty. Some apps surface their
     * placeholder through [AccessibilityNodeInfo.getText] while the field is empty, without
     * setting [AccessibilityNodeInfo.isShowingHintText], so it would otherwise be spliced in as
     * existing text. WhatsApp's empty compose box, for instance, reports `text="Message"` with
     * no hint and no caret, and a transcript came out as "Message hello".
     *
     * A focused editable field with real content always reports a caret, so non-empty text with
     * no text selection is a leaked placeholder, not content. Hint equality is deliberately not
     * used: a field may legitimately hold text equal to its hint (e.g. a typed "Search").
     */
    private fun fieldText(node: AccessibilityNodeInfo): CharSequence {
        val text = node.text
        if (node.isShowingHintText || text.isNullOrEmpty()) return ""
        if (node.textSelectionStart < 0 && node.textSelectionEnd < 0) return ""
        return text
    }

    /** Splices [transcript] in at the field's selection. `false` if the field refused. */
    private fun setText(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)) return false
        val current: CharSequence = fieldText(node)
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
        val current: CharSequence = fieldText(node)
        copyToClipboard(pasteText(current, node.textSelectionStart, node.textSelectionEnd, transcript))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
