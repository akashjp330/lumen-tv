package tv.lumen.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette from the mockups. */
object C {
    val Ink = Color(0xFF0B0D12)
    val Rail = Color(0xFF0E1118)
    val Surface = Color(0xFF151A24)
    val Surface2 = Color(0xFF1A2030)
    val Raised = Color(0xFF1E2535)
    val Line = Color(0xFF242B3B)
    val Text = Color(0xFFF2F4F8)
    val Text2 = Color(0xFFB4BCCC)
    val Muted = Color(0xFF8A93A6)
    val Faint = Color(0xFF6B7487)
    val Live = Color(0xFFE5484D)
    val Track = Color(0xFF2A3142)
}

val LocalAccent = staticCompositionLocalOf { Color(0xFF4C8DFF) }

object T {
    val hero = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = C.Text, lineHeight = 38.sp)
    val h1 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.Text)
    val h2 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Text)
    val body = TextStyle(fontSize = 12.sp, color = C.Text2, lineHeight = 17.sp)
    val title = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.Text)
    val meta = TextStyle(fontSize = 9.sp, color = C.Muted)
    val chip = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.8.sp)
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.Ink, surface = C.Surface, onSurface = C.Text, onBackground = C.Text,
            primary = Color(0xFF4C8DFF), onPrimary = C.Ink,
        ),
        content = content,
    )
}
