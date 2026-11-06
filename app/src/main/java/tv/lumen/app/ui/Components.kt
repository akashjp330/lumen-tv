package tv.lumen.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lumen.core.Channel

/**
 * The one focusable building block. Scales and rings when the remote lands on it;
 * OK clicks; the ≡ (menu) key calls [onMenu].
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    color: Color = C.Surface,
    focusedColor: Color = color,
    scale: Float = 1.06f,
    ring: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocus?.invoke(focused) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .then(if (focused && ring) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onPreviewKeyEvent { e ->
                if (onMenu != null && e.type == KeyEventType.KeyUp &&
                    e.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU
                ) { onMenu(); true } else false
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content(focused) }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    Focusable(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = when { primary -> Color.White; selected -> C.Text; else -> C.Surface2 },
        focusedColor = Color.White,
        scale = 1.05f,
        ring = false,
        onFocus = onFocus,
    ) { focused ->
        Text(
            text,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            style = T.title.copy(
                fontSize = 11.sp,
                color = if (focused || primary || selected) C.Ink else C.Text2,
                fontWeight = if (primary || selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

/** Provider logo, or a monogram when the provider sent none or it fails to load. */
@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier, monogramSize: Dp = 44.dp) {
    var failed by remember(channel.logo) { mutableStateOf(channel.logo == null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            Monogram(channel.name, monogramSize)
        } else {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                onError = { failed = true },
            )
        }
    }
}

@Composable
fun Monogram(name: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(C.Line),
        contentAlignment = Alignment.Center,
    ) {
        Text(monogramOf(name), style = T.h2.copy(fontSize = (size.value * 0.38f).sp, color = C.Text2))
    }
}

/** "Movie-Action" → "Action", "Series-Drama" → "Drama". */
fun displayCategory(name: String): String =
    name.replace(Regex("""^\s*(movies?|series|vod|tv)\s*[-:|]\s*""", RegexOption.IGNORE_CASE), "").ifBlank { name }

private val noiseWords = setOf("hd", "fhd", "uhd", "4k", "sd", "the", "tv")

fun monogramOf(name: String): String {
    val cleaned = name.replace(Regex("""^\s*([A-Z]{2,3}\s*[:|\-]\s*|\|[^|]*\||(USA|US|UK|CA)\s+(?=\S))"""), "")
    val words = cleaned.split(Regex("""[\s\-_:|]+""")).filter { it.isNotBlank() && it.lowercase() !in noiseWords }
    return when {
        words.isEmpty() -> name.take(2).uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(C.Track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(LocalAccent.current))
    }
}

@Composable
fun LiveChip() {
    Text(
        "LIVE",
        style = T.chip,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(C.Live).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = T.h2, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = T.h2)
        Spacer(Modifier.height(6.dp))
        Text(body, style = T.body, modifier = Modifier.width(360.dp))
    }
}

@Composable
fun OneLine(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(modifier.background(C.Surface2), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(title, style = T.title.copy(color = C.Text2), modifier = Modifier.padding(10.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else {
            AsyncImage(
                model = url, contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onError = { failed = true },
            )
        }
    }
}
