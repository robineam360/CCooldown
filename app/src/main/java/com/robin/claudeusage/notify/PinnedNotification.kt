package com.robin.claudeusage.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette

/**
 * The optional always-on notification: one profile's 5-hour usage as a filled
 * gauge (collapsed) and a full bar panel (expanded). It's silent and ongoing,
 * and re-renders on every poll so the percentage and countdown stay live.
 *
 * The colored surfaces (gauge, panel bars) follow the theme and the warning
 * ladder. The tiny status-bar icon is necessarily monochrome — Android renders
 * small icons as an alpha mask — so it conveys the level through fill only.
 */
object PinnedNotification {

    // v2: LOW (not MIN) so the status-bar icon actually shows. Channel importance
    // is locked after creation, so the level change needs a fresh channel id.
    private const val CHANNEL = "pinned_usage_v2"
    private const val NOTIF_ID = 9100
    const val ACTION_REFRESH = "com.robin.claudeusage.PINNED_REFRESH"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // LOW is fully silent (no sound, no vibration, no heads-up) but, unlike
        // MIN, keeps the status-bar icon. It's a status readout, not an alert.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pinned usage", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The always-on usage notification"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    /** Renders or removes the notification to match current settings + data. */
    fun update(context: Context, cache: UsageCache) {
        val nm = NotificationManagerCompat.from(context)
        if (!cache.pinnedEnabled()) {
            nm.cancel(NOTIF_ID)
            return
        }
        ensureChannel(context)

        val profile = cache.pinnedProfile()
        val label = cache.profileLabel(profile)
        val dark = isNightMode(context)
        val theme = Palette.color(cache.themeColorName(), dark)
        val data = cache.snapshot(profile).data
        val session = data?.session
        val pct = session?.percent
        val use24h = cache.use24hTime()

        val fill = Palette.barColor(pct, theme, dark)
        val gauge = drawGauge(context, pct, fill)
        val smallIcon = drawStatusIcon(context, pct, cache.pinnedIconStyle())

        // The percentage lives in the gauge; keep it out of the title to avoid doubling up.
        val title = "$label · 5-hour window"
        // One compact line so it doesn't wrap next to the gauge on narrow screens.
        // The absolute reset time still shows on the 7-day bar in the expanded panel.
        val collapsedText = buildString {
            if (session?.resetsAt != null) append("Resets ${Fmt.relIn(session.resetsAt)}")
            else append("Not started yet")
            data?.weekly?.percent?.let { append(" · 7-day ${it.toInt()}%") }
        }

        val openApp = PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java).putExtra("profile", profile.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val refresh = PendingIntent.getBroadcast(
            context, NOTIF_ID,
            Intent(context, PinnedRefreshReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Refresh", refresh)

        gauge?.let { builder.setLargeIcon(it) }

        if (data != null) {
            val panel = drawPanel(context, label, data, theme, dark, use24h)
            if (panel != null) {
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(panel)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                        .setSummaryText(collapsedText)
                )
            }
        }

        try {
            nm.notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to show.
        }
    }

    // --- drawing ---

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )

    /**
     * Bold, self-contained gauge for the collapsed large-icon slot (layout A):
     * a faint track ring, a bright usage arc, and a solid filled center disc so
     * the white percentage always reads — even on OEMs that draw a pale backplate
     * behind large icons.
     */
    private fun drawGauge(context: Context, pct: Double?, fill: Color): Bitmap? {
        val size = dp(context, 72f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val stroke = size * 0.11f
        val pad = stroke / 2 + size * 0.03f
        val rect = RectF(pad, pad, size - pad, size - pad)
        val fillArgb = fill.toArgb()

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = fill.copy(alpha = 0.28f).toArgb()
        }
        c.drawArc(rect, 0f, 360f, false, track)

        val fraction = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        if (fraction > 0f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = fillArgb
            }
            // A hair of sweep so even ~1% shows a visible cap rather than nothing.
            c.drawArc(rect, -90f, (360f * fraction).coerceAtLeast(4f), false, arc)
        }

        // Solid center disc — guarantees contrast for the white number.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillArgb }
        c.drawCircle(cx, cx, size * 0.31f, disc)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * (if (pct != null && pct >= 100.0) 0.22f else 0.26f)
        }
        val label = if (pct == null) "—" else "${pct.toInt()}%"
        val baseline = cx - (text.descent() + text.ascent()) / 2f
        c.drawText(label, cx, baseline, text)
        return bmp
    }

    /**
     * Monochrome status-bar icon in the chosen style. Everything is drawn white;
     * the two-tone look (faint track + solid fill) rides on the alpha channel,
     * which the system preserves when it tints the icon.
     */
    private fun drawStatusIcon(context: Context, pct: Double?, iconStyle: String): IconCompat {
        val size = dp(context, 24f).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fraction = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        // Clamp a non-zero fill to a small minimum so the icon still reads at 1-3%.
        val sweep = if (fraction > 0f) fraction.coerceAtLeast(0.09f) else 0f
        val white = AndroidColor.WHITE

        when (iconStyle) {
            "pie" -> {
                val r = size * 0.42f
                val cx = size / 2f
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = size * 0.09f
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
                    style = Paint.Style.STROKE; strokeWidth = size * 0.07f; color = white
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
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (pct != null && pct >= 100.0) size * 0.5f else size * 0.62f
                }
                val label = when {
                    pct == null -> "–"
                    pct >= 100.0 -> "!!"
                    else -> pct.toInt().toString()
                }
                val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
                c.drawText(label, size / 2f, baseline, text)
            }
            else -> { // "ring" (default) — faint full ring + solid arc from the top
                val r = size * 0.38f
                val cx = size / 2f
                val sw = size * 0.14f
                val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = sw; color = white; alpha = 90
                }
                c.drawCircle(cx, cx, r, track)
                if (sweep > 0f) {
                    val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = sw
                        strokeCap = Paint.Cap.ROUND; color = white
                    }
                    c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, false, arc)
                }
            }
        }
        return IconCompat.createWithBitmap(bmp)
    }

    /** The expanded panel: labeled, color-coded bars for 5-hour, 7-day, and model caps. */
    private fun drawPanel(
        context: Context,
        profileLabel: String,
        data: UsageData,
        theme: Color,
        dark: Boolean,
        use24h: Boolean,
    ): Bitmap? {
        // 5-hour is already the gauge + title in the header, so the panel covers
        // the rest: the 7-day window and any per-model caps.
        data class Bar(val label: String, val window: UsageWindow?, val trailing: String)
        val bars = buildList {
            add(Bar("7-day", data.weekly, data.weekly?.resetsAt?.let { Fmt.dayTime(it, use24h) } ?: ""))
            for (cap in data.modelCaps) add(Bar(cap.modelName, cap.window, ""))
        }.take(4)

        val width = dp(context, 340f).toInt()
        val rowH = dp(context, 34f)
        val headerH = dp(context, 30f)
        val height = (headerH + rowH * bars.size + dp(context, 8f)).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val onSurface = if (dark) AndroidColor.parseColor("#ECECEC") else AndroidColor.parseColor("#1F1F1F")
        val muted = if (dark) AndroidColor.parseColor("#9E9E9E") else AndroidColor.parseColor("#6B6B6B")
        val labelW = dp(context, 84f)
        val valueW = dp(context, 96f)
        val barLeft = labelW
        val barRight = width - valueW
        val barThick = dp(context, 7f)

        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface; textSize = dp(context, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText("$profileLabel usage", dp(context, 2f), dp(context, 20f), header)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onSurface; textSize = dp(context, 13f) }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; textSize = dp(context, 12f); textAlign = Paint.Align.RIGHT
        }

        bars.forEachIndexed { i, bar ->
            val cy = headerH + rowH * i + rowH / 2f
            c.drawText(bar.label, dp(context, 2f), cy + dp(context, 4.5f), labelPaint)

            val fill = Palette.barColor(bar.window?.percent, theme, dark)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.copy(alpha = 0.22f).toArgb() }
            val radius = barThick / 2f
            c.drawRoundRect(RectF(barLeft, cy - barThick / 2, barRight, cy + barThick / 2), radius, radius, track)

            val fraction = ((bar.window?.percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0f) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
                val end = barLeft + (barRight - barLeft) * fraction
                c.drawRoundRect(RectF(barLeft, cy - barThick / 2, end.coerceAtLeast(barLeft + barThick), cy + barThick / 2), radius, radius, paint)
            }

            val value = (bar.window?.percent?.let { "${it.toInt()}%" } ?: "—") +
                if (bar.trailing.isNotEmpty()) " · ${bar.trailing}" else ""
            c.drawText(value, width - dp(context, 2f), cy + dp(context, 4.5f), valuePaint)
        }
        return bmp
    }
}
