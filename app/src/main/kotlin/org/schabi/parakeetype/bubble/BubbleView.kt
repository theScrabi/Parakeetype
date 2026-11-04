package org.schabi.parakeetype.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import org.schabi.parakeetype.R
import kotlin.math.abs

/** What the bubble shows. */
enum class BubbleState { Idle, Loading, Listening, Finishing }

/**
 * The floating microphone button: a round, draggable view. A tap calls [onTap]; a drag moves
 * the window through [onDrag] (raw screen deltas) and ends with [onDragEnd].
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(context) {

    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val icon = ImageView(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val idleColor = context.getColor(android.R.color.system_accent1_600)
    private val idleIconColor = context.getColor(android.R.color.system_accent1_0)
    private val listeningColor = context.getColor(R.color.bubble_listening)

    var state: BubbleState = BubbleState.Idle
        set(value) {
            field = value
            render()
        }

    init {
        val size = dp(BUBBLE_SIZE_DP)
        val iconSize = dp(ICON_SIZE_DP)
        setBackground(background)
        elevation = dp(6).toFloat()
        addView(icon, LayoutParams(iconSize, iconSize, Gravity.CENTER))
        minimumWidth = size
        minimumHeight = size
        contentDescription = context.getString(R.string.bubble_start_dictation)
        render()
    }

    /** Microphone level while listening, 0..1; grows the bubble slightly. */
    fun setLevel(level: Float) {
        if (state != BubbleState.Listening) return
        val scale = 1f + 0.18f * level.coerceIn(0f, 1f)
        animate().scaleX(scale).scaleY(scale).setDuration(80).start()
    }

    private fun render() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        when (state) {
            BubbleState.Idle -> {
                background.setColor(idleColor)
                icon.setImageResource(R.drawable.ic_bubble_mic)
                icon.setColorFilter(idleIconColor)
                alpha = IDLE_ALPHA
                contentDescription = context.getString(R.string.bubble_start_dictation)
            }

            BubbleState.Loading -> {
                background.setColor(idleColor)
                icon.setImageResource(R.drawable.ic_bubble_mic)
                icon.setColorFilter(idleIconColor)
                alpha = 0.6f
                contentDescription = context.getString(R.string.bubble_model_loading)
            }

            BubbleState.Listening -> {
                background.setColor(listeningColor)
                icon.setImageResource(R.drawable.ic_bubble_stop)
                icon.setColorFilter(0xFFFFFFFF.toInt())
                alpha = 1f
                contentDescription = context.getString(R.string.bubble_stop_dictation)
            }

            BubbleState.Finishing -> {
                background.setColor(listeningColor)
                icon.setImageResource(R.drawable.ic_bubble_mic)
                icon.setColorFilter(0xFFFFFFFF.toInt())
                alpha = 0.6f
                contentDescription = context.getString(R.string.bubble_transcribing)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.makeMeasureSpec(dp(BUBBLE_SIZE_DP), MeasureSpec.EXACTLY)
        super.onMeasure(size, size)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    onDrag((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX; lastY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    onDragEnd()
                } else {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    performClick()
                }
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val BUBBLE_SIZE_DP = 52
        private const val ICON_SIZE_DP = 26
        private const val IDLE_ALPHA = 0.85f
    }
}
