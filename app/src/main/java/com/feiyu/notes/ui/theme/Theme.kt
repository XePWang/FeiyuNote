package com.feiyu.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R

// Indigo hair, lake-blue accents and warm paper, shared by every screen.
private val LightColors = lightColorScheme(
    primary = Color(0xFF384B78), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FA), onPrimaryContainer = Color(0xFF172B50),
    secondary = Color(0xFF21677C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEFF5), onSecondaryContainer = Color(0xFF164B5D),
    tertiary = Color(0xFF765775), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DDF0), onTertiaryContainer = Color(0xFF523950),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF202B3F),
    surface = Color(0xFFFCFCFF), onSurface = Color(0xFF202B3F),
    surfaceVariant = Color(0xFFE9EEF5), onSurfaceVariant = Color(0xFF4B586B),
    surfaceContainer = Color(0xFFEEF2F8), surfaceContainerLow = Color(0xFFF2F5FA),
    surfaceContainerHigh = Color(0xFFE6ECF5), outline = Color(0xFF738095),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C9F5), onPrimary = Color(0xFF1E3259),
    primaryContainer = Color(0xFF30466F), onPrimaryContainer = Color(0xFFDDE5FA),
    secondary = Color(0xFF95D1E6), onSecondary = Color(0xFF003545),
    secondaryContainer = Color(0xFF194B5D), onSecondaryContainer = Color(0xFFDCEFF5),
    tertiary = Color(0xFFE0BBDD), onTertiary = Color(0xFF412B40),
    tertiaryContainer = Color(0xFF594158), onTertiaryContainer = Color(0xFFF2DDF0),
    background = Color(0xFF111824), onBackground = Color(0xFFE2E8F4),
    surface = Color(0xFF151E2D), onSurface = Color(0xFFE2E8F4),
    surfaceVariant = Color(0xFF2D384B), onSurfaceVariant = Color(0xFFBBC6D9),
    surfaceContainer = Color(0xFF1C2636), surfaceContainerLow = Color(0xFF192232),
    surfaceContainerHigh = Color(0xFF273245), outline = Color(0xFF8A97AC),
)
@OptIn(ExperimentalTextApi::class)
private val ReadingFont = FontFamily(
    Font(R.font.noto_sans_sc, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_sans_sc, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.noto_sans_sc, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
private val Base = Typography()
private val ReadingType = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = ReadingFont),
    displayMedium = Base.displayMedium.copy(fontFamily = ReadingFont),
    displaySmall = Base.displaySmall.copy(fontFamily = ReadingFont),
    headlineLarge = Base.headlineLarge.copy(fontFamily = ReadingFont),
    headlineMedium = Base.headlineMedium.copy(fontFamily = ReadingFont),
    headlineSmall = Base.headlineSmall.copy(fontFamily = ReadingFont),
    titleLarge = Base.titleLarge.copy(fontFamily = ReadingFont),
    titleMedium = Base.titleMedium.copy(fontFamily = ReadingFont),
    titleSmall = Base.titleSmall.copy(fontFamily = ReadingFont),
    bodyLarge = Base.bodyLarge.copy(fontFamily = ReadingFont),
    bodyMedium = Base.bodyMedium.copy(fontFamily = ReadingFont),
    bodySmall = Base.bodySmall.copy(fontFamily = ReadingFont),
    labelLarge = Base.labelLarge.copy(fontFamily = ReadingFont),
    labelMedium = Base.labelMedium.copy(fontFamily = ReadingFont),
    labelSmall = Base.labelSmall.copy(fontFamily = ReadingFont),
)

@Composable
fun FeiyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ReadingType,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content,
    )
}
