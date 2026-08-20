package com.robin.claudeusage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * CCRM-30 (Estimate Honesty): a line whose number the app *inferred* rather than
 * measured. A muted trailing ⓘ marks it; tapping the line toggles a one-line
 * provenance note underneath — subtle at rest, explicit on demand, nothing for a
 * screenshot to misread as data. Numbers the server reported render as plain
 * Text and never come through here: marking a measurement would hedge it.
 *
 * (The approved wireframe sketched a dotted underline; Compose text has no
 * dotted decoration and a solid underline reads as a link, so the marker is the
 * ⓘ glyph — same interaction, different affordance. Recorded in the wireframe.)
 */
@Composable
fun EstimateLine(
    text: String,
    provenance: String,
    color: Color,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    fontWeight: FontWeight? = null,
) {
    var open by remember { mutableStateOf(false) }
    val marker = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Column {
        Text(
            buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = marker)) { append(" ⓘ") }
            },
            style = style,
            fontWeight = fontWeight,
            color = color,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            Spacer(Modifier.height(3.dp))
            ProvenanceNote(provenance)
        }
    }
}

/** The revealed provenance line — one sentence, quiet chip background. */
@Composable
fun ProvenanceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}
