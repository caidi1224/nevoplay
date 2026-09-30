package com.shilapi.xcertplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The host UI's design tokens and its three non-stock widgets.
 *
 * The language is a terminal one: flat surfaces, 1px hairlines instead of shadows, one monospace
 * family throughout, and colour reserved for four meanings — accent (on / connected), warning,
 * error, and dimmed text. Every size here is expressed in dp or sp so a different panel resolution
 * only changes the density; the extra scale pass in `CarPlayHostActivity.applyHostScale` handles
 * panels that are physically much wider or narrower than the 900dp reference.
 */
object HostUi {
    /**
     * One palette, two sets of values. The vehicle decides which one is active (see
     * `CarPlayHostActivity.applyTheme`): dark follows the existing terminal palette, light is the
     * same layout on an off-white paper.
     */
    data class Palette(
        val bg: Int,
        val surface: Int,
        val surface2: Int,
        val surface3: Int,
        val line: Int,
        val line2: Int,
        val text: Int,
        val dim: Int,
        val faint: Int,
        val accent: Int,
        val accentDim: Int,
        val accentWash: Int,
        val warn: Int,
        val error: Int,
        /** Fill behind the on-screen log, which sits on top of the CarPlay picture. */
        val logScrim: Int,
    )

    private val DARK = Palette(
        bg = 0xff0b0d0e.toInt(),
        surface = 0xff101314.toInt(),
        surface2 = 0xff171a1c.toInt(),
        surface3 = 0xff1e2225.toInt(),
        line = 0xff23272a.toInt(),
        line2 = 0xff31363a.toInt(),
        text = 0xffe6e9e9.toInt(),
        dim = 0xff8b9398.toInt(),
        faint = 0xff5c6469.toInt(),
        accent = 0xff7fcd9a.toInt(),
        accentDim = 0xff3f6b52.toInt(),
        accentWash = 0x227fcd9a,
        warn = 0xffe3b341.toInt(),
        error = 0xffe5534b.toInt(),
        logScrim = 0xe8101314.toInt(),
    )

    /**
     * Off-white paper. The accent is darkened from the dark theme's mint: the same green at that
     * lightness would be unreadable as text on a light surface.
     */
    private val LIGHT = Palette(
        bg = 0xfff1eee6.toInt(),
        surface = 0xfffbf9f4.toInt(),
        surface2 = 0xfff0ece2.toInt(),
        surface3 = 0xffffffff.toInt(),
        line = 0xffe1dcd1.toInt(),
        line2 = 0xffcfc7b8.toInt(),
        text = 0xff1d1c19.toInt(),
        dim = 0xff6b665c.toInt(),
        faint = 0xff8a8474.toInt(),
        accent = 0xff2f7d52.toInt(),
        accentDim = 0xff9cc5ac.toInt(),
        accentWash = 0x1f2f7d52,
        warn = 0xff9a6b12.toInt(),
        error = 0xffc0392b.toInt(),
        logScrim = 0xe8fbf9f4.toInt(),
    )

    @Volatile
    private var active: Palette = DARK

    fun useDarkTheme(dark: Boolean) {
        active = if (dark) DARK else LIGHT
    }

    val isDark: Boolean get() = active === DARK

    val BG: Int get() = active.bg
    val SURFACE: Int get() = active.surface
    val SURFACE_2: Int get() = active.surface2
    val SURFACE_3: Int get() = active.surface3
    val LINE: Int get() = active.line
    val LINE_2: Int get() = active.line2
    val TEXT: Int get() = active.text
    val DIM: Int get() = active.dim
    val FAINT: Int get() = active.faint
    val ACCENT: Int get() = active.accent
    val ACCENT_DIM: Int get() = active.accentDim
    val ACCENT_WASH: Int get() = active.accentWash
    val WARN: Int get() = active.warn
    val ERROR: Int get() = active.error
    val LOG_SCRIM: Int get() = active.logScrim

    /**
     * The panel width the sizes here were authored for. A wider panel scales up, a narrower one
     * down - but text and spacing do not scale by the same amount, which is the whole trick: on a
     * phone the padding has to collapse to a phone's proportions while the text stays readable.
     */
    const val REFERENCE_WIDTH_DP = 1280f
    const val MIN_TEXT_SCALE = 0.8f
    const val MAX_TEXT_SCALE = 1.5f
    const val MIN_SPACE_SCALE = 0.35f
    const val MAX_SPACE_SCALE = 1.5f

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun mono(): Typeface = Typeface.MONOSPACE

    fun monoBold(): Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun rounded(
        context: Context,
        radiusDp: Int,
        fill: Int,
        stroke: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(context, strokeWidthDp).coerceAtLeast(1), stroke)
    }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) monoBold() else mono()
        includeFontPadding = false
    }

    fun hairline(context: Context, color: Int = LINE): View = View(context).apply {
        setBackgroundColor(color)
    }

    /** A tappable text control: the terminal's stand-in for a button. */
    fun chip(
        context: Context,
        label: String,
        style: ChipStyle = ChipStyle.NORMAL,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 20f
        typeface = mono()
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        minHeight = dp(context, 56)
        setPadding(dp(context, 22), dp(context, 12), dp(context, 22), dp(context, 12))
        when (style) {
            ChipStyle.PRIMARY -> {
                setTextColor(BG)
                typeface = monoBold()
                background = rounded(context, 8, ACCENT)
            }
            ChipStyle.NORMAL -> {
                setTextColor(TEXT)
                background = rounded(context, 8, SURFACE_2, LINE_2)
            }
            ChipStyle.DANGER -> {
                setTextColor(ERROR)
                background = rounded(context, 8, SURFACE_2, ERROR)
            }
        }
    }

    enum class ChipStyle { PRIMARY, NORMAL, DANGER }
}

/**
 * A category card: a header line, then rows separated by hairlines. This is the terminal "block"
 * the whole settings screen is made of.
 */
class HostBlock(
    context: Context,
    title: String,
    tail: String = "",
) : LinearLayout(context) {

    private val rows = LinearLayout(context)

    init {
        orientation = VERTICAL
        background = HostUi.rounded(context, 10, HostUi.SURFACE, HostUi.LINE)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                HostUi.dp(context, 26),
                HostUi.dp(context, 12),
                HostUi.dp(context, 26),
                HostUi.dp(context, 12),
            )
        }
        header.addView(
            HostUi.text(context, title.uppercase(), 17f, HostUi.FAINT).apply {
                letterSpacing = 0.18f
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (tail.isNotEmpty()) {
            header.addView(
                HostUi.text(context, tail, 17f, HostUi.FAINT),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            HostUi.hairline(context),
            LayoutParams(LayoutParams.MATCH_PARENT, HostUi.dp(context, 1)),
        )
        rows.orientation = VERTICAL
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Adds one row, hairlined off from the one above it. */
    fun addRow(row: View) {
        if (rows.childCount > 0) {
            rows.addView(
                HostUi.hairline(context),
                LayoutParams(LayoutParams.MATCH_PARENT, HostUi.dp(context, 1)),
            )
        }
        row.setPadding(
            HostUi.dp(context, 26),
            HostUi.dp(context, 14),
            HostUi.dp(context, 26),
            HostUi.dp(context, 14),
        )
        rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Adds a row that keeps its own horizontal padding, for full-bleed rows such as a slider. */
    fun addRow(row: View, keepPadding: Boolean) {
        if (keepPadding) {
            if (rows.childCount > 0) {
                rows.addView(
                    HostUi.hairline(context),
                    LayoutParams(LayoutParams.MATCH_PARENT, HostUi.dp(context, 1)),
                )
            }
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            addRow(row)
        }
    }
}

/**
 * A binary setting as text: `[ ON ]` / `[ OFF ]`. Same touch target as the stock Switch it
 * replaces, but it reads as a config file rather than as a coloured pill.
 */
class HostToggle(context: Context) : TextView(context) {

    private var changeListener: ((View, Boolean) -> Unit)? = null
    private var checkedState = false

    init {
        textSize = 20f
        typeface = HostUi.mono()
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        minWidth = HostUi.dp(context, 112)
        minHeight = HostUi.dp(context, 56)
        setPadding(HostUi.dp(context, 18), HostUi.dp(context, 12), HostUi.dp(context, 18), HostUi.dp(context, 12))
        setOnClickListener { isChecked = !checkedState }
        render()
    }

    var isChecked: Boolean
        get() = checkedState
        set(value) {
            if (checkedState == value) return
            checkedState = value
            render()
            changeListener?.invoke(this, value)
        }

    fun setOnCheckedChangeListener(listener: (View, Boolean) -> Unit) {
        changeListener = listener
    }

    /** Re-syncs the drawing with state changed elsewhere, without firing the listener. */
    fun setCheckedQuietly(value: Boolean) {
        if (checkedState == value) return
        checkedState = value
        render()
    }

    private fun render() {
        text = if (checkedState) "[ ON ]" else "[ OFF ]"
        if (checkedState) {
            setTextColor(HostUi.ACCENT)
            background = HostUi.rounded(context, 8, HostUi.ACCENT_WASH, HostUi.ACCENT_DIM)
        } else {
            setTextColor(HostUi.DIM)
            background = HostUi.rounded(context, 8, HostUi.SURFACE_2, HostUi.LINE_2)
        }
    }
}

/**
 * A setting that is one of a few ordered values, drawn as a segmented track instead of a stock
 * SeekBar: the segments line up with the values, so the current one can be counted at a glance.
 */
class HostSlider(context: Context) : View(context) {

    interface OnChangeListener {
        fun onProgressChanged(slider: HostSlider, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(slider: HostSlider) = Unit
        fun onStopTrackingTouch(slider: HostSlider) = Unit
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segmentRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var changeListener: OnChangeListener? = null
    private var maxValue = 0
    private var progressValue = 0
    private var tracking = false
    private var downX = 0f
    private var downY = 0f

    /** Set by the host's scale pass so the track grows with the panel, not with the font. */
    var renderScale: Float = 1f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var max: Int
        get() = maxValue
        set(value) {
            maxValue = value.coerceAtLeast(0)
            progressValue = progressValue.coerceIn(0, maxValue)
            invalidate()
        }

    var progress: Int
        get() = progressValue
        set(value) {
            progressValue = value.coerceIn(0, maxValue)
            invalidate()
        }

    fun setOnChangeListener(listener: OnChangeListener) {
        changeListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The visual bar may shrink with the panel, but the strip that accepts a finger may not.
        val area = maxOf(BAR_AREA_HEIGHT_DP * renderScale, MIN_TOUCH_HEIGHT_DP)
        val desired = HostUi.dp(context, area.roundToInt())
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = (maxValue + 1).coerceAtLeast(1)
        val gap = HostUi.dp(context, (4 * renderScale).roundToInt()).coerceAtLeast(1)
        val barHeight = HostUi.dp(context, (BAR_HEIGHT_DP * renderScale).roundToInt())
            .coerceIn(HostUi.dp(context, MIN_BAR_HEIGHT_DP), HostUi.dp(context, BAR_HEIGHT_DP.toInt()))
        val radius = HostUi.dp(context, (2 * renderScale).roundToInt()).toFloat().coerceAtLeast(1f)
        val totalGap = gap * (count - 1)
        val segmentWidth = ((width - totalGap).toFloat() / count).coerceAtLeast(1f)
        val top = (height - barHeight) / 2f
        for (index in 0 until count) {
            val left = index * (segmentWidth + gap)
            segmentRect.set(left, top, left + segmentWidth, top + barHeight)
            paint.color = if (index <= progressValue) HostUi.ACCENT else HostUi.LINE_2
            canvas.drawRoundRect(segmentRect, radius, radius, paint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    val deltaX = abs(event.x - downX)
                    val deltaY = abs(event.y - downY)
                    // Let a vertical drag scroll the settings list; only a horizontal one grabs us.
                    if (deltaX < touchSlop || deltaX < deltaY) return true
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    changeListener?.onStartTrackingTouch(this)
                }
                applyTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) {
                    tracking = true
                    changeListener?.onStartTrackingTouch(this)
                }
                applyTouch(event.x)
                changeListener?.onStopTrackingTouch(this)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tracking) changeListener?.onStopTrackingTouch(this)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float) {
        val count = (maxValue + 1).coerceAtLeast(1)
        val index = ((x / width.coerceAtLeast(1).toFloat()) * count).toInt().coerceIn(0, maxValue)
        if (index == progressValue) return
        progressValue = index
        invalidate()
        changeListener?.onProgressChanged(this, index, true)
    }

    private companion object {
        const val BAR_HEIGHT_DP = 20f
        const val MIN_BAR_HEIGHT_DP = 8
        /** Bar plus slack: a touch target tall enough for a dashboard, not just for the drawing. */
        const val BAR_AREA_HEIGHT_DP = 48f
        const val MIN_TOUCH_HEIGHT_DP = 40f
    }
}

/** Shorthand for the row shape used all over the settings screen: label left, value right. */
fun hostRow(context: Context, label: View, value: View? = null, hint: String? = null): LinearLayout {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val top = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (value != null) {
        top.addView(value, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = HostUi.dp(context, 16)
        })
    }
    column.addView(top, LinearLayout.LayoutParams(-1, -2))
    if (hint != null) {
        column.addView(
            HostUi.text(context, hint, 18f, HostUi.FAINT).apply {
                setPadding(0, HostUi.dp(context, 8), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    return column
}

/**
 * Scales a freshly built subtree: type by [textScale], and everything that takes up room - padding,
 * margins, fixed sizes, touch targets - by [spaceScale]. Touch targets keep a floor, because a
 * control that has shrunk to a phone's proportions still has to be hit with a finger.
 */
fun View.applyHostScale(textScale: Float, spaceScale: Float) {
    if (this is HostSlider) renderScale = spaceScale
    if (this is TextView) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize * textScale)
        if (minWidth > 0) {
            minimumWidth = (minWidth * spaceScale).roundToInt()
                .coerceAtLeast(HostUi.dp(context, MIN_TOUCH_WIDTH_DP))
        }
        if (minHeight > 0) {
            minimumHeight = (minHeight * spaceScale).roundToInt()
                .coerceAtLeast(HostUi.dp(context, MIN_TOUCH_HEIGHT_DP))
        }
    }
    setPadding(
        (paddingLeft * spaceScale).roundToInt(),
        (paddingTop * spaceScale).roundToInt(),
        (paddingRight * spaceScale).roundToInt(),
        (paddingBottom * spaceScale).roundToInt(),
    )
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
        params.setMargins(
            (params.leftMargin * spaceScale).roundToInt(),
            (params.topMargin * spaceScale).roundToInt(),
            (params.rightMargin * spaceScale).roundToInt(),
            (params.bottomMargin * spaceScale).roundToInt(),
        )
        if (params.width > 0) params.width = (params.width * spaceScale).roundToInt()
        if (params.height > 0) params.height = (params.height * spaceScale).roundToInt()
        layoutParams = params
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).applyHostScale(textScale, spaceScale)
    }
}

private const val MIN_TOUCH_WIDTH_DP = 24
private const val MIN_TOUCH_HEIGHT_DP = 44
