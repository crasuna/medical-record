package com.loveluke.medicalrecord.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal40 = Color(0xFF006B5B)
private val Teal80 = Color(0xFF54DBC1)
private val Teal90 = Color(0xFF76F8DD)
private val Teal10 = Color(0xFF00201A)
private val Teal20 = Color(0xFF00382F)
private val Neutral99 = Color(0xFFF5FBF8)
private val Neutral10 = Color(0xFF171D1B)
private val Neutral20 = Color(0xFF2B3230)
private val Neutral90 = Color(0xFFDDE5E1)
private val Neutral95 = Color(0xFFEBF2EF)
private val NeutralVariant30 = Color(0xFF3F4945)
private val NeutralVariant80 = Color(0xFFBEC9C4)
private val Amber40 = Color(0xFF795900)
private val Amber80 = Color(0xFFF9BD24)
private val Error40 = Color(0xFFBA1A1A)
private val Error80 = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF76F8DD),
    onPrimaryContainer = Teal10,
    secondary = Color(0xFF4A635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA3),
    onTertiaryContainer = Color(0xFF261900),
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = NeutralVariant30,
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C4),
    error = Error40,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Teal90,
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1C352E),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCDE9DF),
    tertiary = Amber80,
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFDEA3),
    background = Color(0xFF0E1513),
    onBackground = Neutral90,
    surface = Color(0xFF0E1513),
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = NeutralVariant80,
    outline = Color(0xFF89938F),
    outlineVariant = NeutralVariant30,
    error = Error80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val MedicalTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val MedicalShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Immutable
data class MedicalSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightSemanticColors = MedicalSemanticColors(
    success = Color(0xFF176B45),
    onSuccess = Color.White,
    successContainer = Color(0xFFA2F4C1),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF735C00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFE16B),
    onWarningContainer = Color(0xFF231B00),
)

private val DarkSemanticColors = MedicalSemanticColors(
    success = Color(0xFF86D8A6),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF00522F),
    onSuccessContainer = Color(0xFFA2F4C1),
    warning = Color(0xFFE6C44E),
    onWarning = Color(0xFF3C2F00),
    warningContainer = Color(0xFF564500),
    onWarningContainer = Color(0xFFFFE16B),
)

private val LocalMedicalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

object MedicalRecordThemeTokens {
    val semanticColors: MedicalSemanticColors
        @Composable get() = LocalMedicalSemanticColors.current

    val contentMaxWidth = 1_120.dp
    val formMaxWidth = 760.dp
    val detailMaxWidth = 920.dp
}

@Composable
fun MedicalRecordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMedicalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MedicalTypography,
            shapes = MedicalShapes,
            content = content,
        )
    }
}
