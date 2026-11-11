package com.foulfoxhacks.mellozone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Brand palette taken from mellozone.site. The site is dark-only; so is the app. */
object Brand {
    val Ink = Color(0xFF070712)
    val InkSoft = Color(0xFF101020)
    val InkRaised = Color(0xFF17172B)
    val Paper = Color(0xFFF7F3FF)
    val PaperMuted = Color(0xFFD8D0E8)
    val Blue = Color(0xFF135CFF)
    val BlueBright = Color(0xFF3F86FF)
    val Violet = Color(0xFF7637FF)
    val Pink = Color(0xFFEF8FCB)
    val PinkLight = Color(0xFFF4B8E4)
    val Sky = Color(0xFFA8C7FF)
    val Line = Color(0xFF2B2B45)
}

private fun scheme(pureBlack: Boolean) = darkColorScheme(
    primary = Brand.Pink,
    onPrimary = Color(0xFF2B0A20),
    primaryContainer = Brand.Blue,
    onPrimaryContainer = Color.White,
    secondary = Brand.Sky,
    onSecondary = Color(0xFF07204A),
    secondaryContainer = Color(0xFF1B2A55),
    onSecondaryContainer = Brand.Sky,
    tertiary = Color(0xFFB9A1FF),
    onTertiary = Color(0xFF1E0A52),
    background = if (pureBlack) Color.Black else Brand.Ink,
    onBackground = Brand.Paper,
    surface = if (pureBlack) Color.Black else Brand.Ink,
    onSurface = Brand.Paper,
    surfaceVariant = Brand.InkRaised,
    onSurfaceVariant = Brand.PaperMuted,
    surfaceContainerLowest = if (pureBlack) Color.Black else Color(0xFF05050D),
    surfaceContainerLow = if (pureBlack) Color(0xFF08080F) else Color(0xFF0C0C1A),
    surfaceContainer = if (pureBlack) Color(0xFF0C0C16) else Brand.InkSoft,
    surfaceContainerHigh = Brand.InkRaised,
    surfaceContainerHighest = Color(0xFF1F1F38),
    outline = Color(0xFF4A4A6A),
    outlineVariant = Brand.Line,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val Display = FontFamily.SansSerif

private val MelloTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontFamily = Display, fontWeight = FontWeight.Black,
            fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
        ),
        headlineMedium = headlineMedium.copy(
            fontFamily = Display, fontWeight = FontWeight.Black,
            fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.3).sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontFamily = Display, fontWeight = FontWeight.ExtraBold,
            fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

val LeadStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)

@Composable
fun MelloTheme(pureBlack: Boolean, textScale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    // The reader's text-size setting multiplies the system font scale.
    val density = Density(base.density, base.fontScale * textScale)
    MaterialTheme(colorScheme = scheme(pureBlack), typography = MelloTypography) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}
