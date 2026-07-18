package com.robin.claudeusage.widget

import android.appwidget.AppWidgetManager
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 * Shown by the launcher when a widget is placed (android:configure). Picks the
 * profile for any widget, plus the bar kind for the single-bar widget.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val isBarWidget = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className?.endsWith("BarWidgetReceiver") == true

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
                    ConfigScreen(isBarWidget) { profile, bar ->
                        WidgetPrefs(this@WidgetConfigActivity).save(appWidgetId, profile, bar)
                        val scopeActivity = this@WidgetConfigActivity
                        // Render the widget with its new config, then confirm.
                        kotlinx.coroutines.MainScope().launch {
                            try {
                                if (isBarWidget) BarWidget().updateAll(scopeActivity)
                                else UsageWidget().updateAll(scopeActivity)
                            } catch (_: Exception) {
                            }
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                            )
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConfigScreen(isBarWidget: Boolean, onDone: (Profile, String?) -> Unit) {
    var profile by remember { mutableStateOf(Profile.PERSONAL) }
    var bar by remember { mutableStateOf("session") }
    rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Widget setup", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        Text("Profile", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Profile.entries) {
                FilterChip(
                    selected = profile == p,
                    onClick = { profile = p },
                    label = { Text(p.label) },
                )
            }
        }

        if (isBarWidget) {
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

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { onDone(profile, if (isBarWidget) bar else null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add widget") }
    }
}
