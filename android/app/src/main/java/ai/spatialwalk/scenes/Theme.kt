package ai.spatialwalk.scenes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF2F6BFF)
private val BlueDark = Color(0xFF7DA6FF)

private val LightColors = lightColorScheme(
    primary = Blue,
    background = Color(0xFFF2F4F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8ECF4),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    background = Color(0xFF11141A),
    surface = Color(0xFF1A1E26),
    surfaceVariant = Color(0xFF262B35),
)

@Composable
fun TutoringTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
