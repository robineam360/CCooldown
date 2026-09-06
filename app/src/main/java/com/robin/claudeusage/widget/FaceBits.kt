package com.robin.claudeusage.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.robin.claudeusage.R
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.RingRenderer

// Shared pieces of the three ring/pace faces (CCRM-39/40/41). Same idea as the
// internals in UsageWidget.kt — one file so the faces can't drift apart.

/** The amber warning treatment: stale data or a failed fetch, never an error screen. */
internal val amberPillBg = ColorProvider(day = Color(0xFFFBEED3), night = Color(0xFF3A342A))
internal val amberPillInk = ColorProvider(day = Color(0xFF8A5A00), night = Color(0xFFFDD663))

/** The one-line amber pill; the message truncates rather than wraps. */
@Composable
internal fun FacePill(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 10.sp) {
    Text(
        text,
        style = TextStyle(color = amberPillInk, fontSize = fontSize),
        maxLines = 1,
        modifier = GlanceModifier
            .background(amberPillBg)
            .cornerRadius(99.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * The pill's text for [FaceState.STALE] / [FaceState.FETCH_ERROR]. The error case
 * shows CCRM-27 (Error Taxonomy)'s short label rather than the raw status — the
 * remediation and detail live in-app.
 */
internal fun pillText(state: FaceState, snapshot: Snapshot, profile: Profile): String? = when (state) {
    FaceState.STALE -> "⚠ Updated ${Fmt.ago(snapshot.fetchedAt)}"
    // CCRM-57 (Provider Plumbing): the short label names this account's own vendor.
    FaceState.FETCH_ERROR ->
        "⚠ ${com.robin.claudeusage.data.ErrorKind.fromKey(snapshot.lastStatusKind).short(profile.provider)}"
    else -> null
}

/** The ↻ glyph: asks the app to poll this profile (180-second floor applies). */
@Composable
internal fun RefreshGlyph(profile: Profile, sizeDp: Dp = 18.dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_refresh),
        contentDescription = "Refresh",
        modifier = GlanceModifier.size(sizeDp).clickable(
            actionRunCallback<RefreshAction>(actionParametersOf(PROFILE_PARAM to profile.key))
        ),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
    )
}

/** [RingRenderer.draw] with the dp→px conversion the Glance layer needs. */
internal fun ringBitmap(
    context: Context,
    sizeDp: Float,
    strokeDp: Float,
    percent: Double?,
    elapsedPercent: Double?,
    accent: Color,
    dark: Boolean,
    /** The widgets' "Show red past the pace mark" toggle; the tick ignores it. */
    showOverPace: Boolean = true,
): Bitmap {
    val density = context.resources.displayMetrics.density
    return RingRenderer.draw(
        sizePx = (sizeDp * density).toInt().coerceAtLeast(1),
        strokePx = strokeDp * density,
        percent = percent,
        elapsedPercent = elapsedPercent,
        accent = accent,
        dark = dark,
        showOverPace = showOverPace,
    )
}

/** The signed-out face: never silently swaps to another profile's data. */
@Composable
internal fun NotSignedInFace() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "No account connected",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            "Open ${LocalContext.current.getString(R.string.app_name)} to sign in",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}
