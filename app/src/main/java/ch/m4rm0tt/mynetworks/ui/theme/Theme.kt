package ch.m4rm0tt.mynetworks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette : noir profond, violet modéré et désaturé, jaune réservé aux favoris. */
object Mn {
    val Black = Color(0xFF07060A)
    val Surface = Color(0xFF110F16)
    val Surface2 = Color(0xFF19161F)
    val Surface3 = Color(0xFF231F2C)
    val Violet = Color(0xFF9A86D8)
    val VioletSoft = Color(0xFF7E6BB8)
    val VioletDeep = Color(0xFF3B3160)
    val VioletInk = Color(0xFF2A2440)
    val Text = Color(0xFFEDEAF4)
    val TextDim = Color(0xFFA8A2B6)
    val TextFaint = Color(0xFF6F697D)
    val Legendary = Color(0xFFF2C94C)
    val Danger = Color(0xFFE38A8A)
    val Rim = Color(0x33FFFFFF)
}

private val scheme = darkColorScheme(
    primary = Mn.Violet,
    onPrimary = Mn.Black,
    primaryContainer = Mn.VioletDeep,
    onPrimaryContainer = Mn.Text,
    secondary = Mn.VioletSoft,
    onSecondary = Mn.Black,
    tertiary = Mn.Legendary,
    background = Mn.Black,
    onBackground = Mn.Text,
    surface = Mn.Surface,
    onSurface = Mn.Text,
    surfaceVariant = Mn.Surface2,
    onSurfaceVariant = Mn.TextDim,
    surfaceContainerHigh = Mn.Surface2,
    surfaceContainerHighest = Mn.Surface3,
    outline = Mn.TextFaint,
    error = Mn.Danger,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MyNetworksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
