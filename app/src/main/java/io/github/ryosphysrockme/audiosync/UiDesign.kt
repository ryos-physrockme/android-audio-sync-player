package io.github.ryosphysrockme.audiosync

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/**
 * Small, dependency-free visual system for the compact audio-sync screen.
 * Kept separate from playback/matching state and safe to use on API 26+.
 */
internal object UiDesign {
    const val BACKGROUND = 0xff11121e.toInt()
    const val SURFACE = 0xff222435.toInt()
    const val SURFACE_RAISED = 0xff2b2e43.toInt()
    const val SURFACE_STRONG = 0xff343650.toInt()
    const val OUTLINE = 0xff41435c.toInt()
    const val PRIMARY = 0xffa08bff.toInt()
    const val PRIMARY_DARK = 0xff6550d4.toInt()
    const val FOREGROUND = 0xfff4f2ff.toInt()
    const val SECONDARY = 0xffafb0c7.toInt()
    const val SUCCESS = 0xff78e0bd.toInt()
    const val ERROR = 0xffffa9b0.toInt()

    fun dp(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Int, bordered: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (bordered) setStroke(dp(context, 1), OUTLINE)
        }

    fun primary(context: Context, radiusDp: Int = 19): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xff9877ff.toInt(), PRIMARY_DARK)
        ).apply { cornerRadius = dp(context, radiusDp).toFloat() }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int = FOREGROUND,
        medium: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun button(
        button: Button,
        background: GradientDrawable,
        textColor: Int,
        textSizeSp: Float = 14f
    ) {
        button.backgroundTintList = null
        button.background = background
        button.setTextColor(textColor)
        button.textSize = textSizeSp
        button.isAllCaps = false
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
    }
}
