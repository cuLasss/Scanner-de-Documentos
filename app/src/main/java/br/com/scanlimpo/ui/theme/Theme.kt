package br.com.scanlimpo.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F0E2),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DE),
    onSecondaryContainer = Color(0xFF062019),
    tertiary = Color(0xFF9A5B13),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2F1500),
    background = Color(0xFFF5F8F5),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5F8F5),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF3EF),
    surfaceContainer = Color(0xFFE9EEEA),
    surfaceContainerHigh = Color(0xFFE3E9E4),
    surfaceContainerHighest = Color(0xFFDDE4DF),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF62D8BB),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF80F5D6),
    secondary = Color(0xFFB4CCC2),
    onSecondary = Color(0xFF1F352D),
    secondaryContainer = Color(0xFF354B43),
    onSecondaryContainer = Color(0xFFD0E8DE),
    tertiary = Color(0xFFFFB86C),
    onTertiary = Color(0xFF4B2800),
    tertiaryContainer = Color(0xFF6B3C00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** O editor fica sempre escuro: ajuda a enxergar o documento, em qualquer tema. */
val EditorBackground = Color(0xFF111513)
val EditorSurface = Color(0xFF1B211E)
val EditorOnSurface = Color(0xFFE6ECE8)
val EditorMuted = Color(0xFF9AA6A0)
val EditorAccent = Color(0xFF62D8BB)

/** Se o tema em uso é o escuro, seja por escolha da pessoa ou por seguir o sistema. */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ScanLimpoTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Raiz de cada tela do app: aplica o tema escolhido nas configurações, inclusive nas barras do sistema. */
@Composable
fun AppTheme(activity: ComponentActivity, content: @Composable () -> Unit) {
    val mode by activity.app.settings.themeMode.collectAsStateWithLifecycle()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DisposableEffect(dark) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activity.window.isNavigationBarContrastEnforced = false
        onDispose {}
    }
    ScanLimpoTheme(dark, content)
}

private const val TRANSPARENT = 0
