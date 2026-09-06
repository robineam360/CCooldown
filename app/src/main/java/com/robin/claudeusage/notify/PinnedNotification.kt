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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.BarGeometry
import com.robin.claudeusage.ui.BarRenderer
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.UsageIcon
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.ui.providerMarkRes

/**
 * The optional always-on notification: one profile's 5-hour usage as a filled
 * gauge (collapsed) and a full bar panel (expanded). It's silent and ongoing,
 * and re-renders on every poll so the percentage and countdown stay live.
 *
 * The colored surfaces (gauge, panel bars) follow the theme and the warning
 * ladder — and since CCRM-49 (Glyph Legibility) the status-bar icon does too, drawn
 * with the very same [Palette.barColor] value so the glyph and the gauge can never
 * disagree. (The Quick Settings tile still gets the monochrome rendering; it tints
 * whatever it is handed.)
 */
object PinnedNotification {

    // v2: LOW (not MIN) so the status-bar icon actually shows. Channel importance
    // is locked after creation, so the level change needs a fresh channel id.
    private const val CHANNEL = "pinned_usage_v2"

    /**
     * The nominal width every bitmap in this notification is drawn at. RemoteViews
     * never learns the real content width, so both the panel and the collapsed bar
     * assume the same figure and let `fitXY` take up the small remaining difference.
     */
    private const val PANEL_WIDTH_DP = 340f

    private const val NOTIF_ID = 9100
    const val ACTION_REFRESH = "com.robin.claudeusage.PINNED_REFRESH"

    /** The official Claude Android app — the optional tap target (CCRM-2). */
    val CLAUDE_PACKAGE = Provider.CLAUDE.appPackage

    /** Resolves Claude's launcher intent, or null when it isn't installed. */
    fun claudeLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)

    /**
     * Resolves any provider's launcher intent, or null when it isn't installed
     * (CCRM-56 (Provider Identity) — the "app"/"provider" tap target generalises
     * from Claude-only to whichever provider owns the pinned profile).
     */
    fun providerLaunchIntent(context: Context, provider: Provider): Intent? =
        context.packageManager.getLaunchIntentForPackage(provider.appPackage)

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
        val theme = Palette.color(Palette.accentName(cache, profile), dark)
        val data = cache.snapshot(profile).data
        val session = data?.session
        val pct = session?.percent
        val use24h = cache.use24hTime()
        // CCRM-22 (Used or Left), rev B: every numeric readout flips — the big
        // digits, the gauge text, the number plate, the panel rows. The bars,
        // setProgress() and the status-icon fill keep drawing the spend.
        val left = cache.usageLeft()

        val style = cache.pinnedStyle()
        val fill = Palette.barColor(pct, theme, dark)
        // CCRM-43 (Bar Pace Marks): this surface's own red toggle. The even-pace tick
        // draws regardless; only the colour past it is optional.
        val showOverPace = cache.paceOverOnNotification()
        val sessionElapsed = elapsedPercent(session, Projection.SESSION_MS)
        // CCRM-49 (Glyph Legibility): the status bar keeps colour, so the glyph wears
        // the same severity colour as the gauge below it — one source of truth, and the
        // two can never disagree. CCRM-51 (Rails Gauge): the marks are neutral ink
        // ("time has no severity"), so no pace hue is passed any more; the weekly
        // window still rides along as the flag dot in the needle's hub, and this
        // surface's red toggle now reaches the glyph's over-pace slice too.
        val smallIcon = drawStatusIcon(
            context, pct, cache.pinnedIconStyle(), left,
            sessionElapsed = sessionElapsed,
            fillArgb = fill.toArgb(),
            dark = dark,
            weeklyPct = data?.weekly?.percent,
            weeklyElapsed = elapsedPercent(data?.weekly, Projection.WEEKLY_MS),
            showOverPace = showOverPace,
        )
        val pctText = if (pct == null) "—" else "${Fmt.usageInt(pct, left)}%"
        // The worded form for text slots that have room for the word.
        val pctShort = if (pct == null) "—" else Fmt.usageShort(pct, left)

        // CCRM-23 (Reset Display), Option A: the chosen form leads. The collapsed
        // line has one slot, so it carries the chosen form only; the expanded line
        // keeps both, chosen first.
        val resetClock = cache.resetClock()
        val resetShort = when {
            session?.resetsAt == null -> "not started yet"
            resetClock -> "resets ${Fmt.timeOnly(session.resetsAt, use24h)}"
            else -> "resets ${Fmt.relIn(session.resetsAt)}"
        }
        // Collapsed is the 5-hour window and nothing else: percentage, bar, and when
        // it resets. The 7-day window and the model caps live in the expanded panel,
        // so repeating any of it here would just be duplicate info.
        val resetLong = when {
            session?.resetsAt == null -> "Not started yet"
            resetClock -> "Resets at ${Fmt.timeOnly(session.resetsAt, use24h)} · " +
                Fmt.relIn(session.resetsAt)
            else -> "Resets ${Fmt.relIn(session.resetsAt)} · " +
                Fmt.timeOnly(session.resetsAt, use24h)
        }

        // "progress" is the odd one out: with setProgress() occupying a row, the
        // shade drops the content-text line when collapsed, which is where the reset
        // was. So for that style the title carries the percentage *and* the reset —
        // the title is the only slot guaranteed to survive — and the text line takes
        // the profile identity instead. Every other style keeps the percentage in
        // its own graphic, so the title names the window and the text line resets.
        val progress = style == "progress"
        val title = if (progress) "$pctShort · $resetShort" else "$label · 5-hour window"

        // CCRM-44 (One Surface): with the pinned notification on, this panel carries
        // *every* alert for its profile — the CCBG-12 (Status Icon Swap) conditions,
        // plus folded events and the update strip — silent by the user's explicit
        // choice, readable on demand.
        //
        // The collapsed row gives its one line to the highest-priority strip (the
        // reset time stays in the expanded header, per the approved CCRM-44
        // wireframe); quiet, it keeps the reset line as always. The dot marks the
        // line as a strip, in the strip's own hue.
        val panelState = Conditions.panelFor(context, cache, profile)
        val stale = panelState.stale
        val baseText = if (progress) "$label · 5-hour window" else resetLong
        val collapsedText = panelState.strips.firstOrNull()
            ?.let { withConditionDot(it.short, conditionHue(it, theme, dark)) }
            ?: baseText
        // Expanded has the room the collapsed row doesn't: the panel below carries every
        // condition in full, so the header needs no marker at all.
        val expandedText = baseText

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

        // Not gated on data any more: a condition is worth showing even before the first
        // successful fetch, which is exactly when a sign-in problem is most likely.
        val panel = drawPanel(
            context, label, data, theme, dark, use24h, left, resetClock, showOverPace,
            panelState.strips, panelState.overflow,
        )

        when (style) {
            // A — the number owns the large-icon slot instead of a ring around it.
            "number" -> {
                builder.setLargeIcon(drawNumberTile(context, pct, fill, left))
                panel?.let { builder.setStyle(bigPicture(it, expandedText)) }
            }
            // B — no bitmap at all: the system's own determinate bar, number in the title.
            "progress" -> {
                builder.setProgress(100, (pct ?: 0.0).toInt().coerceIn(0, 100), false)
                panel?.let { builder.setStyle(bigPicture(it, expandedText)) }
            }
            // C — custom views: the largest number the collapsed row can hold.
            "big" -> {
                builder.setCustomContentView(
                    bigNumberView(
                        context, R.layout.notif_big_number, pctText, title, collapsedText,
                        pct, sessionElapsed, fill, theme, dark, showOverPace, null, stale,
                        profile.provider,
                        leftCaption = left && pct != null,
                    )
                )
                builder.setCustomBigContentView(
                    bigNumberView(
                        context, R.layout.notif_big_number_expanded,
                        pctText, title, expandedText,
                        pct, sessionElapsed, fill, theme, dark, showOverPace, panel, stale,
                        profile.provider,
                        leftCaption = left && pct != null,
                    )
                )
                builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            }
            // "gauge" — the original ring.
            else -> {
                drawGauge(context, pct, fill, left)?.let { builder.setLargeIcon(it) }
                panel?.let { builder.setStyle(bigPicture(it, expandedText)) }
            }
        }

        try {
            nm.notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to show.
        }

        armExpiry(context, Conditions.nextExpiry(cache, profile))
    }

    /** The self-redraw that retires an expired strip — see [armExpiry]. */
    const val ACTION_EXPIRE = "com.robin.claudeusage.PINNED_EXPIRE"

    /**
     * CCBG-18 (Strip Lifetime Stamp): arms one alarm at [atMs] to redraw this
     * notification when its soonest strip is due to go.
     *
     * The strip store prunes lazily, on read, and the panel only re-renders on a poll, at
     * the end of `Alerts.evaluate`, or on a Settings change — so without this a 15-minute
     * strip lingers until the next poll, up to a further poll interval. Inexact by
     * choice: a strip leaving a minute late is nothing like a window ping landing late
     * (see `PingScheduler` for why that one is exact), and this must not spend the
     * exact-alarm budget.
     *
     * Always cancels first, so repeated `update` calls replace rather than stack, and a
     * panel with no strips left is a panel with no alarm.
     */
    private fun armExpiry(context: Context, atMs: Long) {
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, NOTIF_ID + 2,
            Intent(context, PinnedRefreshReceiver::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (atMs <= System.currentTimeMillis()) return
        am.set(android.app.AlarmManager.RTC, atMs, pi)
    }

    /**
     * Where a tap on the notification body goes (CCRM-2). "provider" jumps
     * straight into the pinned account's own provider app; anything else —
     * including "provider" when that app isn't installed — opens our own
     * breakdown on the pinned profile. (CCRM-56 (Provider Identity): the stored
     * value "claude" from pre-multi-provider installs reads as "provider".)
     */
    private fun tapIntent(context: Context, cache: UsageCache, profile: Profile): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val target = cache.pinnedTapTarget()
        if (target == "provider" || target == "claude") {
            val launch = providerLaunchIntent(context, profile.provider)
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
        sub: CharSequence,
        pct: Double?,
        elapsed: Double?,
        fill: Color,
        theme: Color,
        dark: Boolean,
        showOverPace: Boolean,
        panel: Bitmap?,
        stale: Boolean,
        provider: Provider,
        /** CCRM-22: the small "LEFT" caption under the number in Left mode. */
        leftCaption: Boolean = false,
    ): RemoteViews = RemoteViews(context.packageName, layout).apply {
        setImageViewResource(R.id.provider_mark, providerMarkRes(provider))
        setInt(R.id.provider_mark, "setColorFilter", theme.toArgb())
        setTextViewText(R.id.pct, pctText)
        setTextColor(R.id.pct, fill.toArgb())
        setViewVisibility(
            R.id.pct_caption,
            if (leftCaption) android.view.View.VISIBLE else android.view.View.GONE,
        )
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.sub, sub)
        setImageViewBitmap(
            R.id.bar, drawBarBitmap(context, pct, elapsed, theme, dark, showOverPace),
        )
        if (panel != null) setImageViewBitmap(R.id.panel, panel)
        // CCBG-12 (Status Icon Swap): a stale reading drawn as crisply as a live one is the
        // actual hazard — the old separate alert said "stale" somewhere else in the shade
        // while the number here still looked authoritative. Fading the number and its bar
        // puts the doubt on the figure itself. The strip below names the cause.
        if (stale) {
            setFloat(R.id.pct, "setAlpha", 0.5f)
            setFloat(R.id.bar, "setAlpha", 0.45f)
        }
    }

    // --- drawing ---

    /**
     * The collapsed row's 5-hour bar, with its pace mark.
     *
     * Rendered at the same nominal width the expanded panel assumes (340 dp) rather
     * than the old `h × 80`. The ImageView is still `fitXY` — RemoteViews can't tell
     * us the real content width — but at roughly 1:1 the residual stretch is a few
     * percent instead of the 2.4× horizontal squeeze the old aspect produced, which
     * flattened the tick to about 0.19 h and skewed the fill→red boundary off the
     * vertical (CCRM-43 (Bar Pace Marks) wireframe rev B, D4). The tick's *position*
     * was always exact; this is about its shape.
     */
    private fun drawBarBitmap(
        context: Context,
        pct: Double?,
        elapsed: Double?,
        theme: Color,
        dark: Boolean,
        showOverPace: Boolean,
    ): Bitmap = BarRenderer.draw(
        widthPx = dp(context, PANEL_WIDTH_DP),
        heightPx = dp(context, 8f),
        percent = pct,
        elapsedPercent = elapsed,
        accent = theme,
        dark = dark,
        showOverPace = showOverPace,
    )

    /**
     * Layout A: the large-icon slot as a solid tile with the number filling it.
     * The ring is gone on purpose — dropping it is what buys the digits their size.
     */
    private fun drawNumberTile(context: Context, pct: Double?, fill: Color, left: Boolean): Bitmap {
        val size = dp(context, 72f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, plate)

        // CCRM-22 rev B: the plate digits flip too; three digits (Left's possible
        // 100) already step down below.
        val label = if (pct == null) "—" else Fmt.usageInt(pct, left).toString()
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
    private fun drawGauge(context: Context, pct: Double?, fill: Color, left: Boolean): Bitmap? {
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
        // CCRM-22 rev B: the ring's text flips too — the arc still draws the spend.
        val label = if (pct == null) "—" else "${Fmt.usageInt(pct, left)}%"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillArgb
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Slightly larger now that it isn't boxed into the disc; four glyphs
            // ("100%" either way) step back down.
            textSize = size * (if (label.length >= 4) 0.26f else 0.30f)
        }
        val baseline = cx - (text.descent() + text.ascent()) / 2f
        c.drawText(label, cx, baseline, text)
        return bmp
    }

    /** The status-bar icon; the drawing itself is shared with the QS tile. */
    private fun drawStatusIcon(
        context: Context,
        pct: Double?,
        iconStyle: String,
        left: Boolean,
        sessionElapsed: Double?,
        fillArgb: Int?,
        dark: Boolean,
        weeklyPct: Double?,
        weeklyElapsed: Double?,
        showOverPace: Boolean,
    ): IconCompat = IconCompat.createWithBitmap(
        UsageIcon.draw(
            context, pct, iconStyle, left, sessionElapsed, fillArgb, dark,
            weeklyPct, weeklyElapsed, showOverPace,
        )
    )

    /**
     * The collapsed row's condition marker: a coloured dot ahead of the reset line.
     *
     * A span rather than a second view, because the collapsed layout has no slot to spare and
     * `setContentText` has to carry the same string for the styles that don't use a custom
     * view.
     *
     * U+25CF BLACK CIRCLE at full text size, deliberately with no size span. The
     * glyph is drawn centred about 0.3 em above the baseline, so it lines up with the text
     * beside it on its own; scaling it to 60% to look "dot-sized" moved that centre to
     * 0.18 em and visibly dropped it below the line — and made it small enough to miss.
     * Unscaled it is roughly 7 dp across, matching the panel strip's own 6 dp dot.
     */
    private fun withConditionDot(text: String, hue: Int): CharSequence =
        SpannableString("●  $text").apply {
            setSpan(ForegroundColorSpan(hue), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /**
     * A fault gets a fixed red — it must not be themed away by an accent that happens to be
     * green. A warning takes the user's own accent, which is what the rest of the panel is
     * already drawn in.
     */
    private fun conditionHue(
        condition: Conditions.Condition,
        theme: Color,
        dark: Boolean,
    ): Int = when {
        condition.error && dark -> AndroidColor.parseColor("#E0705A")
        condition.error -> AndroidColor.parseColor("#B3402A")
        else -> theme.toArgb()
    }

    /**
     * Greedy word wrap for the condition detail. Bounded at three lines because the strip's
     * height is what displaces a model-cap bar — an unbounded sentence could push every bar
     * out of the panel. The last line is ellipsised rather than dropped silently; the full
     * text is one tap away in the app.
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                if (line.isNotEmpty()) lines.add(line)
                line = word
                if (lines.size == 3) break
            }
        }
        if (lines.size < 3 && line.isNotEmpty()) lines.add(line)
        return if (lines.size < 3) lines else lines.take(2) + listOf(
            android.text.TextUtils.ellipsize(
                lines[2], android.text.TextPaint(paint), maxWidth,
                android.text.TextUtils.TruncateAt.END,
            ).toString()
        )
    }

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
        data: UsageData?,
        theme: Color,
        dark: Boolean,
        use24h: Boolean,
        /** CCRM-22 (Used or Left) — flips the row readouts; the bars draw the spend. */
        usageLeft: Boolean,
        /** CCRM-23 (Reset Display) — which form leads the 7-day reset line. */
        resetClock: Boolean,
        showOverPace: Boolean,
        conditions: List<Conditions.Condition>,
        overflow: Int = 0,
    ): Bitmap? {
        val width = dp(context, PANEL_WIDTH_DP).toInt()
        val left = dp(context, 2f)
        val right = width - dp(context, 2f)
        val barThick = dp(context, 10f)
        val labelH = dp(context, 19f)
        val subH = dp(context, 15f)
        val rowGap = dp(context, 14f)

        val onSurface = if (dark) AndroidColor.parseColor("#ECECEC") else AndroidColor.parseColor("#1F1F1F")
        val muted = if (dark) AndroidColor.parseColor("#9E9E9E") else AndroidColor.parseColor("#6B6B6B")

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface; textSize = dp(context, 13.5f)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; textSize = dp(context, 12f)
        }

        // CCBG-12 (Status Icon Swap): condition strips borrow the bar rows' own two type
        // sizes rather than bringing a third — labelPaint for the title, subPaint for the
        // detail — so the panel reads as one thing. Separation comes from a tint, not a
        // rule: a rule made the strip look heavier than the bars it introduces.
        val condPadH = dp(context, 9f)
        val condPadV = dp(context, 7f)
        val condRadius = dp(context, 8f)
        val condDot = dp(context, 6f)
        val condTextLeft = left + condPadH + condDot + dp(context, 7f)
        val condTextWidth = right - condPadH - condTextLeft
        val condTitlePaint = Paint(labelPaint).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val wrapped = conditions.map { wrapText(it.detail, subPaint, condTextWidth) }
        val condHeights = wrapped.map { condPadV * 2f + labelH + it.size * subH }

        data class Bar(val label: String, val window: UsageWindow?, val sub: String)
        // Conditions displace model caps, never the 7-day window — it is the only bar the
        // expanded panel exists to carry, so it holds its place however many strips appear.
        val barBudget = (4 - conditions.size).coerceAtLeast(1)
        val bars = if (data == null) emptyList() else buildList {
            add(
                Bar(
                    "7-day", data.weekly,
                    // CCRM-23 (Reset Display): both forms, chosen first. The panel
                    // has the room, and a 7-day clock needs its weekday to be honest.
                    data.weekly?.resetsAt?.let {
                        if (resetClock) "Resets ${Fmt.dayTime(it, use24h)} · ${Fmt.relIn(it)}"
                        else "Resets ${Fmt.relIn(it)} · ${Fmt.dayTime(it, use24h)}"
                    } ?: "",
                )
            )
            for (cap in data.modelCaps) add(Bar(cap.modelName, cap.window, ""))
        }.take(barBudget)
        if (bars.isEmpty() && conditions.isEmpty() && overflow == 0) return null

        // Every row grows by the tick's overhang above and below, so a mark at the
        // top or bottom edge of the bar can't collide with the label or be cut off.
        // The 2 dp side inset is already wider than half a tick (1.55 dp at this
        // thickness), so a tick at 0% or 100% stays inside the bitmap horizontally.
        val over = BarGeometry.tickOverhang(barThick)

        var height = condHeights.sum() + condHeights.size * dp(context, 12f)
        // CCRM-44 (One Surface): the "+ n more" line when strips overflowed the cap.
        if (overflow > 0) height += subH + dp(context, 12f)
        for (b in bars) {
            height += labelH + dp(context, 7f) + 2f * over + barThick
            if (b.sub.isNotEmpty()) height += subH
            height += rowGap
        }

        val bmp = Bitmap.createBitmap(width, height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Bold and larger, mirroring the collapsed row's headline percentage.
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface; textSize = dp(context, 16f); textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = 0f
        conditions.forEachIndexed { index, condition ->
            val hue = conditionHue(condition, theme, dark)
            val stripHeight = condHeights[index]
            val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (hue and 0x00FFFFFF) or (0x21 shl 24) // 13% alpha
            }
            c.drawRoundRect(
                RectF(left, y, right, y + stripHeight), condRadius, condRadius, tint,
            )
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hue }
            c.drawCircle(
                left + condPadH + condDot / 2f,
                y + condPadV + labelH / 2f,
                condDot / 2f,
                dotPaint,
            )
            c.drawText(
                condition.title, condTextLeft, y + condPadV + labelH - dp(context, 5f),
                condTitlePaint,
            )
            var lineY = y + condPadV + labelH
            for (line in wrapped[index]) {
                c.drawText(line, condTextLeft, lineY + subH - dp(context, 4f), subPaint)
                lineY += subH
            }
            y += stripHeight + dp(context, 12f)
        }

        if (overflow > 0) {
            c.drawText(
                "+ $overflow more — open the app for the rest",
                left + condPadH, y + subH - dp(context, 4f), subPaint,
            )
            y += subH + dp(context, 12f)
        }

        for (bar in bars) {
            val baseline = y + labelH - dp(context, 4f)
            c.drawText(bar.label, left, baseline, labelPaint)
            c.drawText(
                bar.window?.percent?.let { "${Fmt.usageInt(it, usageLeft)}%" } ?: "—",
                right, baseline, valuePaint,
            )
            y += labelH + dp(context, 7f) + over

            val pct = bar.window?.percent
            // Every row in this panel is a 7-day surface — the weekly window and the
            // per-model caps alike — so they all measure their pace against 7 days.
            val elapsed = elapsedPercent(bar.window, Projection.WEEKLY_MS)
            val fill = Palette.barColor(pct, theme, dark)
            val radius = barThick / 2f
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.copy(alpha = 0.25f).toArgb() }
            c.drawRoundRect(RectF(left, y, right, y + barThick), radius, radius, track)

            var fillEnd: Float? = null
            if (pct != null) {
                val end = left + (right - left) * BarGeometry.fillFraction(pct)
                if (end > left) {
                    fillEnd = end.coerceAtLeast(left + barThick).coerceAtMost(right)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
                    c.drawRoundRect(RectF(left, y, fillEnd, y + barThick), radius, radius, paint)
                }
            }

            // The red rides inside a clip of the fill's own rounded rect: straight
            // vertical boundary where the colours meet, and the red covers the fill's
            // rounded tip rather than stopping short of it.
            val segment = BarGeometry.redSegment(pct, elapsed, showOverPace)
            if (segment != null && fillEnd != null) {
                val segLeft = left + (right - left) * segment.first
                if (fillEnd > segLeft) {
                    val clip = Path().apply {
                        addRoundRect(
                            RectF(left, y, fillEnd, y + barThick), radius, radius, Path.Direction.CW,
                        )
                    }
                    c.save()
                    c.clipPath(clip)
                    c.drawRect(
                        RectF(segLeft, y, fillEnd, y + barThick),
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Palette.barColor(100.0, theme, dark).toArgb()
                        },
                    )
                    c.restore()
                }
            }

            if (BarGeometry.showTick(pct, elapsed)) {
                val tickW = BarGeometry.tickWidth(barThick)
                val cx = left + (right - left) * BarGeometry.tickFraction(elapsed!!)
                val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (if (dark) Color(0xFFF2F2F4) else Color(0xFF1D1D1F))
                        .copy(alpha = if (dark) 0.60f else 0.48f).toArgb()
                }
                c.drawRoundRect(
                    RectF(cx - tickW / 2f, y - over, cx + tickW / 2f, y + barThick + over),
                    tickW / 2f, tickW / 2f, tickPaint,
                )
            }
            y += barThick + over

            if (bar.sub.isNotEmpty()) {
                c.drawText(bar.sub, left, y + subH - dp(context, 3f), subPaint)
                y += subH
            }
            y += rowGap
        }
        return bmp
    }
}
