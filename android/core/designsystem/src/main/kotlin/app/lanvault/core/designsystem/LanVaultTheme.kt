package app.lanvault.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF315ECA),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE7EDFA),
        onPrimaryContainer = Color(0xFF234385),
        secondary = Color(0xFF596575),
        secondaryContainer = Color(0xFFE8ECF2),
        onSecondaryContainer = Color(0xFF26354A),
        background = Color(0xFFFAFAF9),
        onBackground = Color(0xFF242729),
        surface = Color(0xFFFAFAF9),
        onSurface = Color(0xFF242729),
        surfaceContainer = Color(0xFFF0F1F2),
        surfaceContainerLow = Color(0xFFF5F5F4),
        surfaceContainerHigh = Color(0xFFE9EBED),
        surfaceVariant = Color(0xFFE9EBED),
        onSurfaceVariant = Color(0xFF656C74),
        outline = Color(0xFF7C838B),
        outlineVariant = Color(0xFFDDDFE2),
        error = Color(0xFFA43832),
        errorContainer = Color(0xFFFFEDEA),
        onErrorContainer = Color(0xFF672822),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFA5BEFF),
        onPrimary = Color(0xFF172F66),
        primaryContainer = Color(0xFF293B5E),
        onPrimaryContainer = Color(0xFFD8E3FF),
        secondary = Color(0xFFBBC6D7),
        secondaryContainer = Color(0xFF354154),
        onSecondaryContainer = Color(0xFFE0E7F2),
        background = Color(0xFF191B1E),
        onBackground = Color(0xFFE8EAED),
        surface = Color(0xFF191B1E),
        onSurface = Color(0xFFE8EAED),
        surfaceContainer = Color(0xFF24272B),
        surfaceContainerLow = Color(0xFF202226),
        surfaceContainerHigh = Color(0xFF2E3237),
        surfaceVariant = Color(0xFF343A43),
        onSurfaceVariant = Color(0xFFAAB1BA),
        outline = Color(0xFF858D98),
        outlineVariant = Color(0xFF3D424A),
        error = Color(0xFFFFB4A9),
        errorContainer = Color(0xFF4B2925),
        onErrorContainer = Color(0xFFFFDAD4),
    )
private val AppTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 30.sp,
                lineHeight = 36.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                lineHeight = 32.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    )

@Composable
fun LanVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(20.dp),
            ),
        content = content,
    )
}
