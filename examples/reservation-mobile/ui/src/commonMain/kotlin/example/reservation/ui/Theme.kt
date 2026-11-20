package example.reservation.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B6F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE8E9),
    onPrimaryContainer = Color(0xFF0B2E31),
    secondary = Color(0xFF5B6B73),
    secondaryContainer = Color(0xFFDFE7EA),
    onSecondaryContainer = Color(0xFF1A2428),
    tertiary = Color(0xFF8A5A2B),
    tertiaryContainer = Color(0xFFF6E3CF),
    onTertiaryContainer = Color(0xFF38200A),
    background = Color(0xFFF6F8F8),
    surface = Color(0xFFF6F8F8),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE6ECEC),
    outlineVariant = Color(0xFFC8D2D3),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCFD2),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF144E52),
    onPrimaryContainer = Color(0xFFCDE8E9),
    secondary = Color(0xFFB9C9D0),
    secondaryContainer = Color(0xFF334046),
    onSecondaryContainer = Color(0xFFDFE7EA),
    tertiary = Color(0xFFE8BF93),
    tertiaryContainer = Color(0xFF583814),
    onTertiaryContainer = Color(0xFFF6E3CF),
    background = Color(0xFF101414),
    surface = Color(0xFF101414),
    surfaceContainerLow = Color(0xFF181D1E),
    surfaceContainerHighest = Color(0xFF2A3031),
    outlineVariant = Color(0xFF3F4849),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun ReservationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
