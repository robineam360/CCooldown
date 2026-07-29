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

        val resetShort =
            if (session?.resetsAt != null) "resets ${Fmt.relIn(session.resetsAt)}"
            else "not started yet"
        // Collapsed is the 5-hour window and nothing else: percentage, bar, and when
        // it resets. The 7-day window and the model caps live in the expanded panel,
        // so repeating any of it here would just be duplicate info.
        val resetLong =
            if (session?.resetsAt != null) "Resets ${Fmt.relIn(session.resetsAt)} · " +
                Fmt.timeOnly(session.resetsAt, use24h)
            else "Not started yet"

        // "progress" is the odd one out: with setProgress() occupying a row, the
        // shade drops the content-text line when collapsed, which is where the reset
        // was. So for that style the title carries the percentage *and* the reset —
        // the title is the only slot guaranteed to survive — and the text line takes
        // the profile identity instead. Every other style keeps the percentage in
        // its own graphic, so the title names the window and the text line resets.
        val progress = style == "progress"
        val title = if (progress) "$pctText · $resetShort" else "$label · 5-hour window"
        val collapsedText = if (progress) "$label · 5-hour window" else resetLong

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
            // Drives the accent the shade uses for the app name and, crucially, the
            // tint of setProgress()'s bar — the only way to colour that bar.
            .setColor(fill.toArgb())
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

    /**
     * Note there's no `bigLargeIcon(null)` here. Hiding it made the 5-hour
     * percentage — the whole point of the gauge and number-tile styles — vanish
     * the moment you expanded the notification. Leaving it unset keeps the large
     * icon in the expanded header, so the percentage stays put and the panel below
     * only has to carry the 7-day window.
     */
    private fun bigPicture(panel: Bitmap, summary: String) =
        NotificationCompat.BigPictureStyle()
            .bigPicture(panel)
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
        val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Three digits (100) need to step down or they'd run past the tile.
            textSize = size * if (label.length >= 3) 0.46f else 0.60f
        }
        // The percent sign rides as a superscript to the right of the digits rather
        // than sitting under them, which is how a percentage normally reads.
        val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = digits.textSize * 0.42f
            alpha = 215
        }

        val digitsW = digits.measureText(label)
        val signW = if (pct == null) 0f else sign.measureText("%")
        val gap = if (pct == null) 0f else size * 0.015f
        // Centre the digits + sign as one group.
        val startX = (size - (digitsW + gap + signW)) / 2f
        val baseline = size * 0.5f - (digits.descent() + digits.ascent()) / 2f

        c.drawText(label, startX, baseline, digits)
        if (pct != null) {
            c.drawText("%", startX + digitsW + gap, baseline - digits.textSize * 0.36f, sign)
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

        // No centre disc: it was the same colour as the arc, so the ring read as a
        // solid blob. The number is drawn in the usage colour on the bare shade
        // instead, which keeps the ring legible as a ring.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillArgb
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Slightly larger now that it isn't boxed into the disc.
            textSize = size * (if (pct != null && pct >= 100.0) 0.26f else 0.30f)
        }
        val label = if (pct == null) "—" else "${pct.toInt()}%"
        val baseline = cx - (text.descent() + text.ascent()) / 2f
        c.drawText(label, cx, baseline, text)
        return bmp
    }

    /** Monochrome status-bar icon; the drawing itself is shared with the QS tile. */
    private fun drawStatusIcon(context: Context, pct: Double?, iconStyle: String): IconCompat =
        IconCompat.createWithBitmap(UsageIcon.draw(context, pct, iconStyle))

    /**
     * The expanded panel: the 7-day window and any per-model caps, each drawn the
     * same way the collapsed row draws the 5-hour window — name on the left, bold
     * percentage on the right, full-width bar underneath, reset time below it.
     *
     * The 5-hour window is deliberately absent: it's the large icon plus the title
     * in the header above, and repeating it here would be duplicate info. There's
     * no profile header either, for the same reason — the title already names it.
     */
    private fun drawPanel(
        context: Context,
        @Suppress("UNUSED_PARAMETER") profileLabel: String,
        data: UsageData,
        theme: Color,
        dark: Boolean,
        use24h: Boolean,
    ): Bitmap? {
        data class Bar(val label: String, val window: UsageWindow?, val sub: String)
        val bars = buildList {
            add(
                Bar(
                    "7-day", data.weekly,
                    data.weekly?.resetsAt?.let { "Resets ${Fmt.dayTime(it, use24h)}" } ?: "",
                )
            )
            for (cap in data.modelCaps) add(Bar(cap.modelName, cap.window, ""))
        }.take(4)
        if (bars.isEmpty()) return null

        val width = dp(context, 340f).toInt()
        val left = dp(context, 2f)
        val right = width - dp(context, 2f)
        val barThick = dp(context, 10f)
        val labelH = dp(context, 19f)
        val subH = dp(context, 15f)
        val rowGap = dp(context, 14f)

        var height = 0f
        for (b in bars) {
            height += labelH + dp(context, 7f) + barThick
            if (b.sub.isNotEmpty()) height += subH
            height += rowGap
        }

        val bmp = Bitmap.createBitmap(width, height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val onSurface = if (dark) AndroidColor.parseColor("#ECECEC") else AndroidColor.parseColor("#1F1F1F")
        val muted = if (dark) AndroidColor.parseColor("#9E9E9E") else AndroidColor.parseColor("#6B6B6B")

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface; textSize = dp(context, 13.5f)
        }
        // Bold and larger, mirroring the collapsed row's headline percentage.
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface; textSize = dp(context, 16f); textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; textSize = dp(context, 12f)
        }

        var y = 0f
        for (bar in bars) {
            val baseline = y + labelH - dp(context, 4f)
            c.drawText(bar.label, left, baseline, labelPaint)
            c.drawText(bar.window?.percent?.let { "${it.toInt()}%" } ?: "—", right, baseline, valuePaint)
            y += labelH + dp(context, 7f)

            val fill = Palette.barColor(bar.window?.percent, theme, dark)
            val radius = barThick / 2f
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.copy(alpha = 0.25f).toArgb() }
            c.drawRoundRect(RectF(left, y, right, y + barThick), radius, radius, track)

            val fraction = ((bar.window?.percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0f) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
                val end = (left + (right - left) * fraction).coerceAtLeast(left + barThick)
                c.drawRoundRect(RectF(left, y, end, y + barThick), radius, radius, paint)
            }
            y += barThick

            if (bar.sub.isNotEmpty()) {
                c.drawText(bar.sub, left, y + subH - dp(context, 3f), subPaint)
                y += subH
            }
            y += rowGap
        }
        return bmp
    }
}
