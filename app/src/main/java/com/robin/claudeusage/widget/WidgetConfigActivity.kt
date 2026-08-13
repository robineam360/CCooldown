package com.robin.claudeusage.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.Palette
import kotlinx.coroutines.launch

/**
 * Shown by the launcher when a widget is placed (android:configure), and again
 * when a placed one is reconfigured (widgetFeatures="reconfigurable"). Picks the
 * profile for any widget, plus the bar kind for the single-bar widget.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancel/back only deletes the widget on the add flow; a reconfigure
        // abandoned this way leaves the instance exactly as it was.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        val kind = when (provider) {
            ComponentName(this, BarWidgetReceiver::class.java) -> WidgetKind.BAR
            ComponentName(this, RingWidgetReceiver::class.java) -> WidgetKind.RING
            ComponentName(this, MiniRingsWidgetReceiver::class.java) -> WidgetKind.MINI_RINGS
            ComponentName(this, PaceWidgetReceiver::class.java) -> WidgetKind.PACE
            else -> WidgetKind.USAGE
        }
        val widgetPrefs = WidgetPrefs(this)
        // Prefs are only written on the first confirm, so their presence is the
        // add-vs-reconfigure test.
        val isReconfigure = widgetPrefs.has(appWidgetId)

        // Render the widget with its new config, then confirm.
        fun renderAndFinish() {
            kotlinx.coroutines.MainScope().launch {
                try {
                    when (kind) {
                        WidgetKind.BAR -> BarWidget().updateAll(this@WidgetConfigActivity)
                        WidgetKind.RING -> RingWidget().updateAll(this@WidgetConfigActivity)
                        WidgetKind.MINI_RINGS -> MiniRingsWidget().updateAll(this@WidgetConfigActivity)
                        WidgetKind.PACE -> PaceWidget().updateAll(this@WidgetConfigActivity)
                        WidgetKind.USAGE -> UsageWidget().updateAll(this@WidgetConfigActivity)
                    }
                } catch (_: Exception) {
                }
                setResult(
                    RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }
        }

        setContent {
            val dark = isSystemInDarkTheme()
            val themeName = remember { UsageCache(this@WidgetConfigActivity).themeColorName() }
            val scheme = when {
                themeName == Palette.DYNAMIC && dark -> dynamicDarkColorScheme(this@WidgetConfigActivity)
                themeName == Palette.DYNAMIC -> dynamicLightColorScheme(this@WidgetConfigActivity)
                dark -> darkColorScheme(primary = Palette.color(themeName, true), onPrimary = Color(0xFF1F1F1F))
                else -> lightColorScheme(primary = Palette.color(themeName, false), onPrimary = Color.White)
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    ConfigScreen(
                        kind = kind,
                        isReconfigure = isReconfigure,
                        initialProfile = widgetPrefs.profileFor(appWidgetId),
                        initialBar = widgetPrefs.barFor(appWidgetId),
                        initialWindow = widgetPrefs.windowFor(appWidgetId),
                        onDone = { profile, bar, window ->
                            widgetPrefs.save(appWidgetId, profile, bar, window)
                            renderAndFinish()
                        },
                        onUseDefaults = {
                            // Drop the per-instance override entirely so the widget
                            // follows the app-wide defaults from now on.
                            widgetPrefs.remove(appWidgetId)
                            renderAndFinish()
                        },
                    )
                }
            }
        }
    }
}

/** Which provider the config screen is configuring — decides which pickers show. */
enum class WidgetKind { USAGE, BAR, RING, MINI_RINGS, PACE }

@androidx.compose.runtime.Composable
private fun ConfigScreen(
    kind: WidgetKind,
    isReconfigure: Boolean,
    initialProfile: Profile,
    initialBar: String,
    initialWindow: String,
    onDone: (Profile, String?, String?) -> Unit,
    onUseDefaults: () -> Unit,
) {
    var profile by remember { mutableStateOf(initialProfile) }
    var bar by remember { mutableStateOf(initialBar) }
    var window by remember { mutableStateOf(initialWindow) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cache = remember { com.robin.claudeusage.data.UsageCache(context) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            if (isReconfigure) "Widget settings" else "Widget setup",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(20.dp))

        Text("Profile", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Profile.entries) {
                FilterChip(
                    selected = profile == p,
                    onClick = { profile = p },
                    label = { Text(cache.profileLabel(p)) },
                )
            }
        }

        if (kind == WidgetKind.BAR) {
            Spacer(Modifier.height(20.dp))
            Text("Show", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((key, label) in BarWidget.BAR_OPTIONS) {
                    FilterChip(
                        selected = bar == key,
                        onClick = { bar = key },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // The ring and pace faces bind one window; the mini-rings face shows
        // them all, so it only ever picks a profile.
        if (kind == WidgetKind.RING || kind == WidgetKind.PACE) {
            Spacer(Modifier.height(20.dp))
            Text("Window", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, label) in listOf("session" to "5-hour", "weekly" to "7-day")) {
                    FilterChip(
                        selected = window == key,
                        onClick = { window = key },
                        label = { Text(label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                onDone(
                    profile,
                    if (kind == WidgetKind.BAR) bar else null,
                    if (kind == WidgetKind.RING || kind == WidgetKind.PACE) window else null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isReconfigure) "Save changes" else "Add widget") }

        if (isReconfigure) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onUseDefaults,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use my defaults") }
        }
    }
}
