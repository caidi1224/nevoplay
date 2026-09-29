package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared visual language for the host UI: iOS 26 style glass surfaces over a dark editorial base.
 *
 * Plain Android views have no backdrop blur, so the glass here is built from what is available:
 * a translucent fill, a light top edge and a darker bottom edge (which reads as glass thickness),
 * large continuous corners, and a soft shadow. Every screen draws from this file so the language
 * stays consistent and a palette change lands everywhere at once.
 */
internal object GlassUi {
    // Surfaces, darkest to lightest. Slightly blue rather than neutral grey.
    val BG_DEEP = Color.rgb(5, 7, 12)
    val BG = Color.rgb(8, 11, 17)
    val SURFACE = Color.argb(26, 255, 255, 255)
    val SURFACE_STRONG = Color.argb(40, 255, 255, 255)
    val SURFACE_SOLID = Color.rgb(16, 21, 30)
    val HAIRLINE = Color.argb(28, 255, 255, 255)
    val HAIRLINE_STRONG = Color.argb(52, 255, 255, 255)

    // Type tiers.
    val TEXT = Color.rgb(242, 245, 251)
    val TEXT_SECONDARY = Color.argb(163, 226, 234, 248)
    val TEXT_TERTIARY = Color.argb(112, 200, 214, 236)

    // One accent, matching the launcher icon. Swap these two lines for iOS blue if wanted.
    val ACCENT = Color.rgb(52, 199, 123)
    val ACCENT_DIM = Color.rgb(38, 138, 90)
    val ACCENT_INK = Color.rgb(6, 22, 14)
    val SWITCH_TRACK_OFF = Color.argb(58, 255, 255, 255)

    const val RADIUS_XL = 34
    const val RADIUS_LG = 26
    const val RADIUS_MD = 16
    const val RADIUS_PILL = 999

    /**
     * Layout scale for the panel in front of us, set once per activity from the display metrics.
     *
     * Authoring sizes assume a 1080p-wide panel; a 2560x1600 head unit needs everything - paddings,
     * icon boxes, button heights - about a third larger, not just the text. Without this, cards and
     * touch targets stay phone-sized on a very large screen.
     */
    @Volatile var scale: Float = 1f

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Density-independent size that also follows the panel's resolution. */
    fun sdp(context: Context, value: Int): Int =
        (value * scale * context.resources.displayMetrics.density).toInt()

    /** Layout params sized by [sdp]; the shell for every fixed-size element. */
    fun sized(context: Context, widthDp: Int, heightDp: Int = widthDp): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(sdp(context, widthDp), sdp(context, heightDp))

    /** Translucent "glass" panel: soft vertical wash, hairline edge, bright top highlight. */
    fun glass(radiusDp: Int, strong: Boolean = false): GradientDrawable {
        val fill = if (strong) SURFACE_STRONG else SURFACE
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(fill, Color.argb((Color.alpha(fill) * 0.6f).toInt(), 255, 255, 255)),
        ).apply {
            cornerRadius = radiusDp.toFloat()
            setStroke(dp1(strong), HAIRLINE_STRONG)
        }
    }

    /** Opaque card for list groups where readability matters more than the glass effect. */
    fun card(radiusDp: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(16, 255, 255, 255)),
        ).apply {
            cornerRadius = radiusDp.toFloat()
            setStroke(dp1(false), HAIRLINE)
        }

    private fun dp1(strong: Boolean): Int = if (strong) 2 else 1

    fun ripple(context: Context, content: GradientDrawable): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
            content,
            null,
        )

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 20f,
        color: Int = TEXT,
        bold: Boolean = false,
        letterSpacing: Float = 0f,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (letterSpacing != 0f) this.letterSpacing = letterSpacing
        includeFontPadding = false
    }

    /** Small uppercase label above a group, the way iOS names a settings section. */
    fun sectionLabel(context: Context, value: String): TextView =
        text(context, value.uppercase(), 13f, TEXT_TERTIARY, bold = true, letterSpacing = 0.16f).apply {
            setPadding(sdp(context, 10), 0, 0, 0)
        }

    /** A grouped card: children stacked with hairline separators drawn by [addRow]. */
    fun group(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = card(RADIUS_LG)
        clipToOutline = true
        elevation = sdp(context, 6).toFloat()
    }

    /** Adds a row to a [group], with a hairline above every row except the first. */
    fun addRow(group: LinearLayout, row: View) {
        if (group.childCount > 0) {
            group.addView(
                View(group.context).apply {
                    setBackgroundColor(HAIRLINE)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp1(false),
                ).apply { leftMargin = dp(group.context, 22) },
            )
        }
        group.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** A label/value row with iOS-like padding. */
    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(sdp(context, 24), sdp(context, 20), sdp(context, 24), sdp(context, 20))
    }

    /** Large tappable destination card used on the settings root. */
    fun categoryCard(
        context: Context,
        title: String,
        subtitle: String,
        glyph: View,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sdp(context, 22), sdp(context, 20), sdp(context, 22), sdp(context, 20))
            background = ripple(context, glass(RADIUS_LG))
            isClickable = true
            isFocusable = true
            clipToOutline = true
            elevation = sdp(context, 4).toFloat()
        }
        row.addView(
            glyph,
            LinearLayout.LayoutParams(sdp(context, 54), sdp(context, 54)).apply {
                rightMargin = sdp(context, 20)
            },
        )
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(context, title, 24f, TEXT, bold = true))
        if (subtitle.isNotEmpty()) {
            labels.addView(
                text(context, subtitle, 16f, TEXT_TERTIARY).apply {
                    setPadding(0, sdp(context, 4), 0, 0)
                },
            )
        }
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(context, "\u203A", 26f, TEXT_TERTIARY))
        row.setOnClickListener { onClick() }
        return row
    }

    /** Rounded icon tile, reusing the accent at low opacity so glyphs stay legible. */
    fun iconTile(context: Context): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = sdp(context, RADIUS_MD).toFloat()
            setColor(Color.argb(40, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)))
            setStroke(dp1(false), Color.argb(70, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)))
        }
    }

    /** Small accent dot used where a category would otherwise need its own icon asset. */
    fun accentDot(context: Context): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
        }
    }

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): View {
        val view = text(context, label, 24f, ACCENT_INK, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, sdp(context, 22), 0, sdp(context, 22))
            background = ripple(
                context,
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(190, 245, 214), ACCENT),
                ).apply { cornerRadius = sdp(context, RADIUS_PILL).toFloat() },
            )
            isClickable = true
            isFocusable = true
            clipToOutline = true
            elevation = sdp(context, 6).toFloat()
        }
        view.setOnClickListener { onClick() }
        return view
    }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit): View {
        val view = text(context, label, 22f, TEXT, bold = false).apply {
            gravity = Gravity.CENTER
            setPadding(0, sdp(context, 20), 0, sdp(context, 20))
            background = ripple(context, glass(RADIUS_PILL))
            isClickable = true
            isFocusable = true
            clipToOutline = true
        }
        view.setOnClickListener { onClick() }
        return view
    }

    fun chip(context: Context, label: String, active: Boolean): TextView =
        text(
            context,
            label,
            17f,
            if (active) TEXT else TEXT_SECONDARY,
            bold = active,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(sdp(context, 22), sdp(context, 14), sdp(context, 22), sdp(context, 14))
            background = GradientDrawable().apply {
                cornerRadius = sdp(context, RADIUS_PILL).toFloat()
                setColor(
                    if (active) {
                        Color.argb(48, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT))
                    } else {
                        SURFACE
                    },
                )
                setStroke(
                    dp1(false),
                    if (active) {
                        Color.argb(110, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT))
                    } else {
                        HAIRLINE
                    },
                )
            }
        }

    /** Applies the iOS-style tint to a framework [android.widget.Switch]. */
    fun tintSwitch(control: android.widget.Switch) {
        control.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.WHITE),
        )
        control.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ACCENT, SWITCH_TRACK_OFF),
        )
    }

    /** Paints a deep vertical wash with two soft colour blooms, as the backdrop for glass. */
    fun backdrop(context: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(10, 16, 28), BG_DEEP, Color.rgb(9, 10, 20)),
        )

    fun spacer(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            sdp(context, heightDp),
        )
    }

    fun widthOf(view: View, widthDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(view.context, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Full-width block with the given top margin, for stacking inside a column. */
    fun block(context: Context, topMarginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = sdp(context, topMarginDp) }

    fun frameBlock(context: Context, topMarginDp: Int = 0, leftMarginDp: Int = 0): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = sdp(context, topMarginDp)
            leftMargin = sdp(context, leftMarginDp)
        }
}
