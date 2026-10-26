package ch.m4rm0tt.mynetworks.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.ui.theme.Mn

/** Remplissage « verre liquide » peu coûteux : dégradés + reflet, sans flou. */
fun Modifier.glass(shape: Shape, tint: Color = Mn.Violet, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                tint.copy(alpha = 0.16f * strength).compositeOver(Mn.Surface2),
                Mn.Surface.copy(alpha = 0.96f),
            )
        )
    )
    .background(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.07f * strength), Color.Transparent),
            radius = 420f,
            center = androidx.compose.ui.geometry.Offset(120f, 0f),
        )
    )
    .border(
        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))),
        shape,
    )

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

/** Appui « gélatineux » : léger écrasement élastique. */
@Composable
fun Modifier.squish(interaction: MutableInteractionSource, reduceMotion: Boolean): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "squish",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale * (if (pressed && !reduceMotion) 1.04f else 1f) }
}

@Composable
fun GlassRoundButton(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    tint: Color = Mn.Text,
    accent: Color = Mn.Violet,
    reduceMotion: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .squish(interaction, reduceMotion)
            .size(size)
            .glass(CircleShape, accent, 1.4f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint, size = size * 0.44f)
    }
}

@Composable
fun GlassAction(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Mn.Text,
    accent: Color = Mn.Violet,
    reduceMotion: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .squish(interaction, reduceMotion)
            .widthIn(min = 64.dp)
            .heightIn(min = 64.dp)
            .glass(RoundedCornerShape(20.dp), accent, if (enabled) 1.2f else 0.4f)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) tint else Mn.TextFaint
        GlyphIcon(glyph, c, size = 22.dp)
        Text(label, color = c, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: Glyph? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) Mn.Violet.copy(alpha = 0.26f) else Mn.Surface3.copy(alpha = 0.7f))
            .border(1.dp, if (selected) Mn.Violet.copy(alpha = 0.7f) else Mn.Rim, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            GlyphIcon(leading, if (selected) Mn.Violet else Mn.TextDim, size = 16.dp)
            Box(Modifier.size(6.dp))
        }
        Text(text, color = if (selected) Mn.Text else Mn.TextDim, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Mn.VioletInk)
            .border(1.dp, Mn.Violet.copy(alpha = 0.25f), shape)
            .padding(start = 12.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Mn.Text, style = MaterialTheme.typography.labelLarge)
        if (onRemove != null) {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Retirer $text" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, Mn.TextDim, size = 14.dp) }
        }
    }
}

/** Portrait ou initiales ; carré arrondi pour les entreprises. */
@Composable
fun Avatar(
    photoId: String?,
    initials: String,
    kind: EntityKind,
    photos: PhotoStore?,
    size: Dp,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    full: Boolean = false,
) {
    val shape: Shape = if (kind == EntityKind.COMPANY) RoundedCornerShape(size * 0.28f) else CircleShape
    var image by remember(photoId) { mutableStateOf(photoId?.let { photos?.cachedThumb(it) }) }
    LaunchedEffect(photoId, full) {
        if (photoId != null && photos != null) {
            image = if (full) photos.full(photoId) ?: photos.thumb(photoId) else photos.thumb(photoId)
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .glass(shape, if (kind == EntityKind.SELF) Mn.Violet else Mn.VioletSoft, 1.8f)
                .border(if (favorite) 2.dp else 0.dp, if (favorite) Mn.Legendary else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            val img: ImageBitmap? = image
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
            } else {
                Text(initials, color = Mn.Text, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.34f).sp)
            }
        }
        if (favorite) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(Mn.Black)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Star, Mn.Legendary, size = size * 0.26f) }
        }
    }
}
