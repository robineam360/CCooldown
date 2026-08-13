package com.robin.claudeusage.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much horizontal room the app window has, on the standard Material
 * breakpoints (600dp / 840dp).
 *
 * Foldables are what this exists for. A Galaxy Fold's cover screen is [COMPACT]
 * (~410dp) and its inner screen is [EXPANDED] (~830dp), so the same running
 * process has to be a phone app and a small tablet app, switching between the
 * two mid-session. Everything here keys off available width rather than a
 * screen-size resource qualifier so split-screen and freeform windows get the
 * layout that fits the window, not the one that fits the panel.
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** True once there's room to stand two full panes beside each other. */
val WidthClass.twoPane: Boolean get() = this != WidthClass.COMPACT

val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/** The measured window width, for the decisions the three buckets are too coarse for. */
val LocalWindowWidth = staticCompositionLocalOf { 0.dp }

/**
 * The measured window height. Only width has breakpoints, but anything that grows
 * with width needs this to stay honest in a short window: a phone in landscape is
 * MEDIUM wide and about 400dp tall, so a chart sized from width alone would end up
 * taller than the window it's in.
 */
val LocalWindowHeight = staticCompositionLocalOf { 0.dp }

/**
 * The width at which a screen can usefully split its own content into two
 * columns. Deliberately not the Material [WidthClass.EXPANDED] boundary: a
 * Fold's inner screen is ~750dp wide, which is only MEDIUM, yet it fits two
 * ~350dp columns comfortably — and it's the device this was written for. Below
 * this, the narrowest rows we have (a label plus three percent chips) start
 * wrapping, which is worse than one honest column.
 */
val TwoColumnMinWidth: Dp = 700.dp

/** True when a screen has room to lay its own content out as two columns. */
@Composable
fun hasTwoColumns(): Boolean = LocalWindowWidth.current >= TwoColumnMinWidth

/**
 * The longest measure we'll draw a single column of cards and prose at. Past
 * roughly this, a column stops reading as a column and starts reading as a
 * stretched phone screen — a usage bar runs the width of your hand while the
 * percentage that labels it sits at the far end.
 */
val ContentMaxWidth: Dp = 640.dp

/**
 * The cap for layouts that already split their own content into columns. They've
 * earned the extra room; past this it would only turn into margin.
 */
val WideMaxWidth: Dp = 1100.dp

/**
 * The cap for a single column whose centrepiece is a chart, deliberately wider than
 * [ContentMaxWidth]. That cap is right for bars and prose and wrong here: it exists
 * to stop a usage bar running the width of your hand, but on this screen the chart is
 * the payload, and holding it to 640dp is what made a Fold's inner display draw the
 * *same size* chart as its cover screen. A slightly wide bar is a real cost, paid
 * knowingly for double the plot area — so don't narrow this back to [ContentMaxWidth]
 * without re-reading CCRM-20.
 *
 * Unconditional on purpose: below this a phone never reaches the cap, so there's no
 * width branch to reason about.
 */
val ChartColumnMaxWidth: Dp = 760.dp

/**
 * Height for a chart that should grow with the room it's given. Keyed off the chart's
 * own width so the aspect ratio stays sane — a 678dp-wide plot at the old fixed 192dp
 * reads as letterboxed — then clamped against the window so a short landscape window
 * doesn't get a chart taller than itself.
 */
fun chartHeight(width: Dp, windowHeight: Dp): Dp {
    val fromWidth = (width * 0.35f).coerceIn(180.dp, 300.dp)
    // A zero window height means nothing has measured yet; trust the width instead of
    // collapsing the chart to nothing.
    return if (windowHeight <= 0.dp) fromWidth else minOf(fromWidth, windowHeight * 0.45f)
}

/**
 * The system animation setting, as one app-wide decision (CCRM-32).
 *
 * A user who turns animations off at the OS level (Settings → Accessibility →
 * Remove animations, or the developer-options animator scale) means it, and the
 * framework honours them everywhere except Compose, which never consults the
 * scale. The policy here is the one OpenQuota's `springMotion` settled on:
 * collapse durations to **zero** and keep the same curve, so there is only one
 * visual behaviour to reason about — never a different animation for the
 * reduced case, just the same one arriving instantly.
 *
 * The pure verdicts live in [Motion] so they're unit-testable; the one Android
 * read is [Motion.scale], and [reduceMotion] glues the two together per
 * composition. Nothing here is cached across compositions on purpose — the
 * scale is a live setting the user can flip mid-session.
 */
object Motion {
    /**
     * True when [animatorScale] means "no animations". Off is exactly 0, but the
     * comparison is written so that garbage (negative, NaN) also lands on the
     * reduced side — a broken read should fail towards stillness, not motion.
     */
    fun reduced(animatorScale: Float): Boolean = !(animatorScale > 0f)

    /** [durationMs], or zero once the scale says animations are off. Deliberately
     * not multiplied through: a 0.5× or 5× developer scale is a debugging tool the
     * framework applies to its own animators, and second-guessing it here would
     * make Compose motion disagree with everything around it twice over. */
    fun collapse(durationMs: Int, animatorScale: Float): Int =
        if (reduced(animatorScale)) 0 else durationMs

    /**
     * The animator scale as it stands right now. Never cache the result — read it
     * again at every decision point, because the setting changes while the app is
     * running and a stale `true` would freeze the app's motion permanently.
     */
    fun scale(context: Context): Float = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
}

/** Whether this composition should skip its animations, read fresh per composition. */
@Composable
fun reduceMotion(): Boolean = Motion.reduced(Motion.scale(LocalContext.current))

/** Measures the window once and publishes the result as [LocalWidthClass]. */
@Composable
fun ProvideWidthClass(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthClass = when {
            maxWidth < 600.dp -> WidthClass.COMPACT
            maxWidth < 840.dp -> WidthClass.MEDIUM
            else -> WidthClass.EXPANDED
        }
        CompositionLocalProvider(
            LocalWidthClass provides widthClass,
            LocalWindowWidth provides maxWidth,
            LocalWindowHeight provides maxHeight,
        ) { content() }
    }
}

/**
 * A vertically scrolling column of content, capped at [maxWidth] and centred in
 * whatever room is left over. On a phone the cap never binds, so this is exactly
 * the layout it replaced; on a wide window it keeps the content readable instead
 * of stretching it edge to edge.
 */
@Composable
fun ContentColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    horizontalPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}
