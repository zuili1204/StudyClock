package com.tiji.studyclock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiji.studyclock.domain.model.ThemeMode

/**
 * 视觉基调：墨、纸、朱。
 * 纸底不用纯白，正文用墨色而不是纯黑；朱砂只用于一处强调（当天行动 / 警示），
 * 其余信息靠字号、字重与细线分层，不靠圆角阴影堆卡片。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2C4756),
    onPrimary = Color(0xFFF6F2E8),
    primaryContainer = Color(0xFFE3E7E4),
    onPrimaryContainer = Color(0xFF1B2C36),
    secondary = Color(0xFFB4472F),
    onSecondary = Color(0xFFFCF5EE),
    secondaryContainer = Color(0xFFF0DED5),
    onSecondaryContainer = Color(0xFF5C2418),
    background = Color(0xFFF4F1E9),
    onBackground = Color(0xFF1B2226),
    surface = Color(0xFFFBF8F0),
    onSurface = Color(0xFF1B2226),
    surfaceVariant = Color(0xFFEDE7DA),
    onSurfaceVariant = Color(0xFF5B6265),
    outline = Color(0xFF7E8481),
    outlineVariant = Color(0xFFD8D0C0),
    error = Color(0xFFA33A2B),
    onError = Color(0xFFFCF5EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FBAC8),
    onPrimary = Color(0xFF102028),
    primaryContainer = Color(0xFF23343D),
    onPrimaryContainer = Color(0xFFCFE0E8),
    secondary = Color(0xFFD97C63),
    onSecondary = Color(0xFF2A130D),
    secondaryContainer = Color(0xFF3B231B),
    onSecondaryContainer = Color(0xFFF0D5CB),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE7E2D6),
    surface = Color(0xFF1A1D20),
    onSurface = Color(0xFFE7E2D6),
    surfaceVariant = Color(0xFF262A2D),
    onSurfaceVariant = Color(0xFFA6A6A0),
    outline = Color(0xFF6E7573),
    outlineVariant = Color(0xFF33383B),
    error = Color(0xFFD2705A),
    onError = Color(0xFF2A130D),
)

/** 数字用衬线字，像账本与旧书；中文标题保持无衬线，避免字重不匹配。 */
private val NumericFamily = FontFamily.Serif

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = NumericFamily, fontSize = 60.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = NumericFamily, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontFamily = NumericFamily, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.0.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

/** 大圆角是“AI 感”的主要来源之一：这里只保留 2–10dp，靠细线而不是曲面分组。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun StudyClockTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
