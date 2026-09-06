package com.robin.claudeusage.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robin.claudeusage.R
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageCache

/**
 * The provider's own mark, not a dot (CCRM-56 (Provider Identity), decision 4) —
 * single-colour hand-traced vector paths of each company's public mark, unmodified
 * in shape. Sizes per the approved wireframe (rev B1): 20dp before the label on
 * cards and tabs, 14dp in chips and on the pinned label line, 28dp in the
 * Add-account sheet. [tint] is the account's resolved accent, or ink on a
 * monochrome surface (decision 7); pass null to draw the drawable's own colour.
 */
@Composable
fun ProviderMark(provider: Provider, size: Dp = 16.dp, tint: Color? = null) {
    Image(
        painter = painterResource(providerMarkRes(provider)),
        contentDescription = provider.displayName,
        modifier = Modifier.size(size),
        colorFilter = tint?.let { ColorFilter.tint(it) },
    )
}

/** The mark's drawable resource, for surfaces that build RemoteViews/Glance views directly. */
@DrawableRes
fun providerMarkRes(provider: Provider): Int = when (provider) {
    Provider.CLAUDE -> R.drawable.ic_provider_claude
    Provider.CHATGPT -> R.drawable.ic_provider_chatgpt
    Provider.ANTIGRAVITY -> R.drawable.ic_provider_gemini
}

/**
 * A tab strip's label: the 20dp mark, always in the account's own resolved
 * accent regardless of selection state (per the wireframe), then the account's
 * own label text. Shared by the Main and History tab strips.
 */
@Composable
fun ProviderTabLabel(cache: UsageCache, profile: Profile, dark: Boolean = appDark()) {
    Row {
        ProviderMark(
            profile.provider,
            size = 20.dp,
            tint = Palette.color(Palette.accentName(cache, profile), dark),
        )
        Spacer(Modifier.width(6.dp))
        Text(cache.profileLabel(profile))
    }
}
