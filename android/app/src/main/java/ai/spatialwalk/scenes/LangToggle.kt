package ai.spatialwalk.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Chinese/English switch. Same shape as the Web and iOS clients: one row of two
 * segments, the active one filled.
 */
@Composable
fun LangToggle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
    ) {
        Segment("中文", Lang.ZH)
        Segment("EN", Lang.EN)
    }
}

@Composable
private fun Segment(label: String, lang: Lang) {
    val active = Localization.lang == lang
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { Localization.select(lang) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
