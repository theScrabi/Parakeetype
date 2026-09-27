package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [enterActionFrom] and [TextInjector.performEnterAction].
 */
class EnterActionTest {

    private fun editor(inputType: Int, imeOptions: Int = 0): EditorInfo = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
    }

    private val singleLine = InputType.TYPE_CLASS_TEXT
    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test
    fun `explicit single-line actions map to their EnterAction`() {
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_SEARCH))).isEqualTo(EnterAction.SEARCH)
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_SEND))).isEqualTo(EnterAction.SEND)
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_GO))).isEqualTo(EnterAction.GO)
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_NEXT))).isEqualTo(EnterAction.NEXT)
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_DONE))).isEqualTo(EnterAction.DONE)
    }

    @Test
    fun `single-line field without an action sends a raw Enter key`() {
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_UNSPECIFIED)))
            .isEqualTo(EnterAction.ENTER_KEY)
        assertThat(enterActionFrom(editor(singleLine, EditorInfo.IME_ACTION_NONE)))
            .isEqualTo(EnterAction.ENTER_KEY)
    }

    @Test
    fun `IME_FLAG_NO_ENTER_ACTION overrides the declared action`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertThat(enterActionFrom(editor(singleLine, options))).isEqualTo(EnterAction.ENTER_KEY)
    }

    @Test
    fun `multi-line field inserts a newline even with a declared action`() {
        assertThat(enterActionFrom(editor(multiLine))).isEqualTo(EnterAction.NEWLINE)
        assertThat(enterActionFrom(editor(multiLine, EditorInfo.IME_ACTION_SEND))).isEqualTo(EnterAction.NEWLINE)
    }

    @Test
    fun `raw TYPE_NULL editor sends a raw Enter key`() {
        assertThat(enterActionFrom(editor(InputType.TYPE_NULL))).isEqualTo(EnterAction.ENTER_KEY)
    }

    @Test
    fun `ENTER_KEY sends a down and up key event and no editor action`() {
        val ic = FakeInputConnection()
        TextInjector(ic, editor(singleLine)).performEnterAction()

        assertThat(ic.keyEventCount).isEqualTo(2)
        assertThat(ic.editorActions).isEmpty()
    }

    @Test
    fun `SEND performs the editor action`() {
        val ic = FakeInputConnection()
        TextInjector(ic, editor(singleLine, EditorInfo.IME_ACTION_SEND)).performEnterAction()

        assertThat(ic.editorActions).containsExactly(EditorInfo.IME_ACTION_SEND)
        assertThat(ic.keyEventCount).isZero()
    }

    @Test
    fun `NEWLINE commits a newline`() {
        val ic = FakeInputConnection()
        TextInjector(ic, editor(multiLine)).performEnterAction()

        assertThat(ic.fieldText).isEqualTo("\n")
        assertThat(ic.editorActions).isEmpty()
    }
}
