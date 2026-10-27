package com.nevcortex.prism.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Technical identity, restrained: neutral surfaces, a teal primary, blue secondary, and semantic
// status colours that are always paired with an icon and text (never colour alone).

val PrismTeal = Color(0xFF5EEAD4)
val PrismTealDark = Color(0xFF0F766E)
val PrismBlue = Color(0xFF8AB4F8)
val PrismBlueDark = Color(0xFF1D4ED8)
val PrismAmber = Color(0xFFFBBF24)
val PrismAmberDark = Color(0xFFB45309)
val PrismRed = Color(0xFFFB7185)
val PrismRedDark = Color(0xFFBE123C)
val PrismGreen = Color(0xFF4ADE80)
val PrismGreenDark = Color(0xFF15803D)

private val DarkColors = darkColorScheme(
    primary = PrismTeal,
    onPrimary = Color(0xFF06201C),
    primaryContainer = Color(0xFF123B35),
    onPrimaryContainer = Color(0xFFA7F3E8),
    secondary = PrismBlue,
    onSecondary = Color(0xFF07121F),
    secondaryContainer = Color(0xFF1B2C44),
    onSecondaryContainer = Color(0xFFD9E6FF),
    tertiary = PrismAmber,
    onTertiary = Color(0xFF241A00),
    background = Color(0xFF0C1117),
    onBackground = Color(0xFFE7EDF5),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFE7EDF5),
    surfaceVariant = Color(0xFF1D2632),
    onSurfaceVariant = Color(0xFF9BA9BC),
    outline = Color(0xFF3A4656),
    outlineVariant = Color(0xFF273240),
    error = PrismRed,
    onError = Color(0xFF2A0710),
    errorContainer = Color(0xFF3B121C),
    onErrorContainer = Color(0xFFFFD7DD)
)

private val LightColors = lightColorScheme(
    primary = PrismTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF06302B),
    secondary = PrismBlueDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0B2447),
    tertiary = PrismAmberDark,
    onTertiary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF536273),
    outline = Color(0xFFB9C4D0),
    outlineVariant = Color(0xFFD7DEE7),
    error = PrismRedDark,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E9),
    onErrorContainer = Color(0xFF4A0514)
)

data class PrismStatusColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val muted: Color,
    val border: Color,
    val successContainer: Color,
    val warningContainer: Color,
    val infoContainer: Color
)

val LocalPrismStatusColors = staticCompositionLocalOf {
    PrismStatusColors(
        success = PrismGreen,
        warning = PrismAmber,
        info = PrismBlue,
        muted = Color(0xFF9BA9BC),
        border = Color(0xFF273240),
        successContainer = Color(0xFF10291C),
        warningContainer = Color(0xFF2A210C),
        infoContainer = Color(0xFF122238)
    )
}

private val PrismTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val PrismShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

@Composable
fun PrismTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val status = if (darkTheme) {
        PrismStatusColors(
            success = PrismGreen,
            warning = PrismAmber,
            info = PrismBlue,
            muted = Color(0xFF9BA9BC),
            border = Color(0xFF273240),
            successContainer = Color(0xFF10291C),
            warningContainer = Color(0xFF2A210C),
            infoContainer = Color(0xFF122238)
        )
    } else {
        PrismStatusColors(
            success = PrismGreenDark,
            warning = PrismAmberDark,
            info = PrismBlueDark,
            muted = Color(0xFF536273),
            border = Color(0xFFD7DEE7),
            successContainer = Color(0xFFE7F7EC),
            warningContainer = Color(0xFFFDF3DD),
            infoContainer = Color(0xFFE8F0FE)
        )
    }
    CompositionLocalProvider(LocalPrismStatusColors provides status) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PrismTypography,
            shapes = PrismShapes,
            content = content
        )
    }
}
