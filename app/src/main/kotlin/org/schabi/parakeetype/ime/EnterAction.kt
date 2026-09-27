package org.schabi.parakeetype.ime

import android.view.inputmethod.EditorInfo

/**
 * The semantic action the Enter/Return key should perform based on the focused editor's
 * [EditorInfo.imeOptions] and [android.text.InputType].
 *
 * Explicit IME action options from [EditorInfo.imeOptions] take priority over the
 * [android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE] flag.
 */
enum class EnterAction {
    /** Insert a newline character - standard Enter for multi-line text fields. */
    NEWLINE,

    /** Trigger a search - e.g. Google search bar, address bar in search mode. */
    SEARCH,

    /** Send a message - e.g. WhatsApp, Messenger, SMS chat input. */
    SEND,

    /** Navigate to a URL / perform the "Go" action - e.g. browser address bar. */
    GO,

    /** Advance to the next input field in a form. */
    NEXT,

    /** Confirm input and (usually) close the keyboard. */
    DONE,

    /**
     * Send a raw `KEYCODE_ENTER` key event. Used for editors that declare no IME action
     * (or opt out of one via [EditorInfo.IME_FLAG_NO_ENTER_ACTION]) and for raw editors
     * ([android.text.InputType.TYPE_NULL], e.g. terminals): such apps handle Enter in a key
     * listener, so [android.view.inputmethod.InputConnection.performEditorAction] would be
     * a silent no-op there.
     */
    ENTER_KEY,
}

/**
 * Derives the correct [EnterAction] from the given [EditorInfo].
 *
 * Explicit IME actions in [EditorInfo.imeOptions] win over the multi-line text flag, so an
 * editor that has both [android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE] set *and* declares
 * [EditorInfo.IME_ACTION_SEND] (like some messaging apps) will produce [EnterAction.SEND].
 *
 * Multi-line fields insert a newline. Single-line fields without an explicit action (or
 * with [EditorInfo.IME_FLAG_NO_ENTER_ACTION]) and raw [android.text.InputType.TYPE_NULL]
 * editors get a raw Enter key event ([EnterAction.ENTER_KEY]).
 */
fun enterActionFrom(editorInfo: EditorInfo): EnterAction {
    // Raw editors (terminals, games) don't implement the text-editing protocol; they only
    // understand key events.
    if (editorInfo.inputType == android.text.InputType.TYPE_NULL) return EnterAction.ENTER_KEY

    // Multi-line flag wins over any declared IME action. A field like WhatsApp's chat input
    // sets both TYPE_TEXT_FLAG_MULTI_LINE *and* IME_ACTION_SEND, but pressing Enter in a
    // multi-line editor should insert a newline, not submit the message.
    val isMultiLine =
        (editorInfo.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    if (isMultiLine) return EnterAction.NEWLINE

    // The app asked for Enter to be a plain key, not its IME action.
    if ((editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EnterAction.ENTER_KEY

    // Single-line field - honour the explicit IME action declared by the app.
    val imeAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
    return when (imeAction) {
        EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
        EditorInfo.IME_ACTION_GO -> EnterAction.GO
        EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
        EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
        EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
        // IME_ACTION_UNSPECIFIED / IME_ACTION_NONE: no action to perform. Sending
        // performEditorAction(DONE) here was ignored by many apps (WebViews, chat fields
        // with a key listener), so the Enter key appeared to do nothing.
        else -> EnterAction.ENTER_KEY
    }
}

