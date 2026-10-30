package dev.piatra.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.piatra.R
import dev.piatra.core.AppFont
import dev.piatra.core.Appearance
import dev.piatra.core.ThemeMode

// The app's look. Every screen reads its colours and text styles from
// MaterialTheme (MaterialTheme.colorScheme.primary, MaterialTheme.typography.titleLarge…),
// so setting them here once changes the whole app.
@Composable
fun PiatraTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDark(appearance.theme)
    val colors = when {
        // Android 12+ builds a palette from the wallpaper ("Material You").
        appearance.wallpaperColors && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> StoneDark
        else -> StoneLight
    }
    MaterialTheme(colorScheme = colors, typography = typographyFor(appearance.font), content = content)
}

@Composable
fun isDark(theme: ThemeMode): Boolean = when (theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

// Fonts bundled in res/font/ (SIL Open Font License; texts in licenses/). Each family
// lists its weights; Material uses Normal for body text, Medium for titles and labels.
// A family without a weight uses its nearest one (Space Mono's titles come out Regular).
private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val FiraMono = FontFamily(
    Font(R.font.fira_mono_regular, FontWeight.Normal),
    Font(R.font.fira_mono_medium, FontWeight.Medium),
    Font(R.font.fira_mono_bold, FontWeight.Bold),
)

private val DmMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

private val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

private val CourierPrime = FontFamily(
    Font(R.font.courier_prime_regular, FontWeight.Normal),
    Font(R.font.courier_prime_bold, FontWeight.Bold),
)

private val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

// Not monospace: Inter and IBM Plex Sans (plain), Atkinson Hyperlegible (made to be easy to
// read), Lora (a serif).
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

private val AtkinsonHyperlegible = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

private val Lora = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_medium, FontWeight.Medium),
    Font(R.font.lora_bold, FontWeight.Bold),
)

fun fontFamilyFor(font: AppFont): FontFamily = when (font) {
    AppFont.JETBRAINS_MONO -> JetBrainsMono
    AppFont.IBM_PLEX_MONO -> IbmPlexMono
    AppFont.FIRA_MONO -> FiraMono
    AppFont.DM_MONO -> DmMono
    AppFont.SPACE_MONO -> SpaceMono
    AppFont.COURIER_PRIME -> CourierPrime
    AppFont.INTER -> Inter
    AppFont.IBM_PLEX_SANS -> IbmPlexSans
    AppFont.ATKINSON_HYPERLEGIBLE -> AtkinsonHyperlegible
    AppFont.LORA -> Lora
    AppFont.SYSTEM -> FontFamily.Default
}

// Material's default sizes and weights, with only the font swapped.
private fun typographyFor(font: AppFont): Typography {
    if (font == AppFont.SYSTEM) return Typography()
    val family = fontFamilyFor(font)
    val t = Typography()
    fun TextStyle.withFont() = copy(fontFamily = family)
    return Typography(
        displayLarge = t.displayLarge.withFont(), displayMedium = t.displayMedium.withFont(), displaySmall = t.displaySmall.withFont(),
        headlineLarge = t.headlineLarge.withFont(), headlineMedium = t.headlineMedium.withFont(), headlineSmall = t.headlineSmall.withFont(),
        titleLarge = t.titleLarge.withFont(), titleMedium = t.titleMedium.withFont(), titleSmall = t.titleSmall.withFont(),
        bodyLarge = t.bodyLarge.withFont(), bodyMedium = t.bodyMedium.withFont(), bodySmall = t.bodySmall.withFont(),
        labelLarge = t.labelLarge.withFont(), labelMedium = t.labelMedium.withFont(), labelSmall = t.labelSmall.withFont(),
    )
}

// Piatra's own colours: a cool slate-blue on stone greys.
// Material's colour roles: primary for buttons and switches, *Container for softer fills,
// surface* for backgrounds (surfaceContainer is the tab bar), on* for text drawn on each.
private val StoneLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF3F6375), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC3E8FD), onPrimaryContainer = Color(0xFF001E2B),
    secondary = Color(0xFF4E616B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E5F1), onSecondaryContainer = Color(0xFF0A1E26),
    tertiary = Color(0xFF5E5B7D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4DFFF), onTertiaryContainer = Color(0xFF1A1836),
    background = Color(0xFFF6FAFD), onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFD), onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE3E9), onSurfaceVariant = Color(0xFF40484D),
    outline = Color(0xFF71787D), outlineVariant = Color(0xFFC0C7CD),
    surfaceTint = Color(0xFF3F6375),
    surfaceDim = Color(0xFFD6DADE), surfaceBright = Color(0xFFF6FAFD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEEF2), surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDFE3E6),
)

private val StoneDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFA6CCE0), onPrimary = Color(0xFF0A3445),
    primaryContainer = Color(0xFF264B5C), onPrimaryContainer = Color(0xFFC3E8FD),
    secondary = Color(0xFFB5C9D4), onSecondary = Color(0xFF20333C),
    secondaryContainer = Color(0xFF364953), onSecondaryContainer = Color(0xFFD1E5F1),
    tertiary = Color(0xFFC7C2EA), onTertiary = Color(0xFF2F2D4C),
    tertiaryContainer = Color(0xFF464364), onTertiaryContainer = Color(0xFFE4DFFF),
    background = Color(0xFF0F1417), onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1417), onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484D), onSurfaceVariant = Color(0xFFC0C7CD),
    outline = Color(0xFF8A9297), outlineVariant = Color(0xFF40484D),
    surfaceTint = Color(0xFFA6CCE0),
    surfaceDim = Color(0xFF0F1417), surfaceBright = Color(0xFF353A3D),
    surfaceContainerLowest = Color(0xFF0A0F12), surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023), surfaceContainerHigh = Color(0xFF252B2E),
    surfaceContainerHighest = Color(0xFF303539),
)
