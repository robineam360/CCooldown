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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.R
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.UsageIcon

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

    /** The official Claude Android app — the optional tap target (CCRM-2). */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Resolves Claude's launcher intent, or null when it isn't installed. */
    fun claudeLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)

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

        val style = cache.pinnedStyle()
        val fill = Palette.barColor(pct, theme, dark)
        val smallIcon = drawStatusIcon(context, pct, cache.pinnedIconStyle())
        val pctText = if (pct == null) "—" else "${pct.toInt()}%"

        // The percentage lives in the gauge/tile, so it stays out of the title —
        // except in "progress", where the bar carries no number of its own.
        val title = if (style == "progress") "$pctText · $label · 5-hour"
        else "$label · 5-hour window"
        // One compact line so it doesn't wrap next to the gauge on narrow screens.
        // The absolute reset time still shows on the 7-day bar in the expanded panel.
        val collapsedText = buildString {
            if (session?.resetsAt != null) append("Resets ${Fmt.relIn(session.resetsAt)}")
            else append("Not started yet")
            data?.weekly?.percent?.let { append(" · 7-day ${it.toInt()}%") }
        }

        val openApp = tapIntent(context, cache, profile)
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

        val panel = data?.let { drawPanel(context, label, it, theme, dark, use24h) }

        when (style) {
            // A — the number owns the large-icon slot instead of a ring around it.
            "number" -> {
                builder.setLargeIcon(drawNumberTile(context, pct, fill))
                panel?.let { builder.setStyle(bigPicture(it, collapsedText)) }
            }
            // B — no bitmap at all: the system's own determinate bar, number in the title.
            "progress" -> {
                builder.setProgress(100, (pct ?: 0.0).toInt().coerceIn(0, 100), false)
                panel?.let { builder.setStyle(bigPicture(it, collapsedText)) }
            }
            // C — custom views: the largest number the collapsed row can hold.
            "big" -> {
                builder.setCustomContentView(
                    bigNumberView(context, R.layout.notif_big_number, pctText, title, collapsedText, pct, fill, null)
                )
                builder.setCustomBigContentView(
                    bigNumberView(
                        context, R.layout.notif_big_number_expanded,
                        pctText, title, collapsedText, pct, fill, panel,
                    )
                )
                builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            }
            // "gauge" — the original ring.
            else -> {
                drawGauge(context, pct, fill)?.let { builder.setLargeIcon(it) }
                panel?.let { builder.setStyle(bigPicture(it, collapsedText)) }
            }
        }

        try {
            nm.notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to show.
        }
    }

    /**
     * Where a tap on the notification body goes (CCRM-2). "claude" jumps straight
     * into the Claude app; anything else — including "claude" when it isn't
     * installed — opens our own breakdown on the pinned profile.
     */
    private fun tapIntent(context: Context, cache: UsageCache, profile: Profile): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (cache.pinnedTapTarget() == "claude") {
            val launch = claudeLaunchIntent(context)
            if (launch != null) {
                // Launched from a notification, so it needs its own task.
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return PendingIntent.getActivity(context, NOTIF_ID + 1, launch, flags)
            }
        }
        return PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java).putExtra("profile", profile.key),
            flags,
        )
    }

    private fun bigPicture(panel: Bitmap, summary: String) =
        NotificationCompat.BigPictureStyle()
            .bigPicture(panel)
            .bigLargeIcon(null as Bitmap?)
            .setSummaryText(summary)

    /**
     * Fills one of the big-number layouts. Text colours come from the layout's
     * theme attributes so they follow the notification shade; only the percentage
     * takes the usage colour, which is the whole point of the style.
     */
    private fun bigNumberView(
        context: Context,
        layout: Int,
        pctText: String,
        title: String,
        sub: String,
        pct: Double?,
        fill: Color,
        panel: Bitmap?,
    ): RemoteViews = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.pct, pctText)
        setTextColor(R.id.pct, fill.toArgb())
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.sub, sub)
        setImageViewBitmap(R.id.bar, drawBarBitmap(context, pct, fill))
        if (panel != null) setImageViewBitmap(R.id.panel, panel)
    }

    // --- drawing ---

    /**
     * A wide, short rounded bar. Drawn at a fixed pixel width and stretched by the
     * ImageView (fitXY), so the rounded caps are built from the height only and
     * survive the scaling.
     */
    private fun drawBarBitmap(context: Context, pct: Double?, fill: Color): Bitmap {
        val h = dp(context, 8f).toInt().coerceAtLeast(8)
        val w = h * 80
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h / 2f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.copy(alpha = 0.25f).toArgb() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, track)

        val fraction = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        if (fraction > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
            c.drawRoundRect(RectF(0f, 0f, (w * fraction).coerceAtLeast(h.toFloat()), h.toFloat()), r, r, paint)
        }
        return bmp
    }

    /**
     * Layout A: the large-icon slot as a solid tile with the number filling it.
     * The ring is gone on purpose — dropping it is what buys the digits their size.
     */
    private fun drawNumberTile(context: Context, pct: Double?, fill: Color): Bitmap {
        val size = dp(context, 72f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, plate)

        val label = if (pct == null) "—" else pct.toInt().toString()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Three digits (100) need to step down or they'd run past the tile.
            textSize = size * if (label.length >= 3) 0.44f else 0.58f
        }
        // Nudged up a touch to leave room for the percent sign beneath the digits.
        val baseline = size * 0.5f - (text.descent() + text.ascent()) / 2f - size * 0.06f
        c.drawText(label, size / 2f, baseline, text)

        if (pct != null) {
            val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = size * 0.2f
                alpha = 210
            }
            c.drawText("%", size / 2f, size * 0.9f, sign)
        }
        return bmp
    }

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

    /** Monochrome status-bar icon; the drawing itself is shared with the QS tile. */
    private fun drawStatusIcon(context: Context, pct: Double?, iconStyle: String): IconCompat =
        IconCompat.createWithBitmap(UsageIcon.draw(context, pct, iconStyle))

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
