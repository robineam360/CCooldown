package com.robin.claudeusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue

/**
 * The small monochrome usage icon, shared by the status bar and the Quick Settings
 * tile. Everything is drawn white and the two-tone look (faint track + solid fill)
 * rides on the alpha channel, because both surfaces tint the icon themselves — they
 * treat it as an alpha mask, so the level can only be conveyed through fill, never
 * through colour.
 */
object UsageIcon {

    /** Styles offered in settings. */
    const val RING = "ring"

    /**
     * [left] is CCRM-22 (Used or Left): it flips only the "number" style's digits
     * (rev B — every numeric readout follows the token). The fills — ring arc, pie
     * slice, battery liquid — always draw the used fraction, and the ≥100% "!!"
     * overflow glyph keys on used in both modes.
     */
    fun draw(context: Context, pct: Double?, style: String, left: Boolean = false): Bitmap {
        val size = dp(context, 24f).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fraction = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        // Clamp a non-zero fill to a small minimum so the icon still reads at 1-3%.
        val sweep = if (fraction > 0f) fraction.coerceAtLeast(0.09f) else 0f
        val white = Color.WHITE

        when (style) {
            "pie" -> {
                val r = size * 0.42f
                val cx = size / 2f
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE; strokeWidth = size * 0.09f
                    color = white; alpha = 90
                }
                c.drawCircle(cx, cx, r, ring)
                if (sweep > 0f) {
                    val slice = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                    c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, true, slice)
                }
            }
            "battery" -> {
                val left = size * 0.3f
                val right = size * 0.7f
                val top = size * 0.12f
                val bottom = size * 0.9f
                val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE; strokeWidth = size * 0.07f; color = white
                }
                c.drawRoundRect(RectF(left, top, right, bottom), size * 0.08f, size * 0.08f, body)
                val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                c.drawRoundRect(RectF(size * 0.42f, size * 0.05f, size * 0.58f, top), 2f, 2f, cap)
                val inset = size * 0.11f
                val fillTop = bottom - inset - (bottom - top - 2 * inset) * fraction
                val liquid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                c.drawRect(left + inset, fillTop, right - inset, bottom - inset, liquid)
            }
            "number" -> {
                val label = when {
                    pct == null -> "–"
                    pct >= 100.0 -> "!!"
                    else -> Fmt.usageInt(pct, left).toString()
                }
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    // "!!" and Left mode's possible "100" both need the step-down.
                    textSize = if (label == "!!" || label.length >= 3) size * 0.5f else size * 0.62f
                }
                val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
                c.drawText(label, size / 2f, baseline, text)
            }
            else -> { // "ring" (default) — faint full ring + solid arc from the top
                val r = size * 0.38f
                val cx = size / 2f
                val sw = size * 0.14f
                val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE; strokeWidth = sw; color = white; alpha = 90
                }
                c.drawCircle(cx, cx, r, track)
                if (sweep > 0f) {
                    val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE; strokeWidth = sw
                        strokeCap = Paint.Cap.ROUND; color = white
                    }
                    c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, false, arc)
                }
            }
        }
        return bmp
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )
}
