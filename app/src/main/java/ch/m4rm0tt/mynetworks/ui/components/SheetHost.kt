package ch.m4rm0tt.mynetworks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.ui.Sheet
import ch.m4rm0tt.mynetworks.ui.theme.Mn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class Rendered(val sheet: Sheet, val state: MutableTransitionState<Boolean>)

/**
 * Pile de panneaux montant du bas, dessinés dans la même fenêtre que le graphe
 * (le graphe garde exactement son zoom et sa position). Le retour Android est géré
 * par l'appelant (BackHandler), le glissement vers le bas ferme le panneau du dessus.
 */
@Composable
fun SheetHost(
    sheets: List<Sheet>,
    reduceMotion: Boolean,
    onDismissTop: () -> Unit,
    content: @Composable (Sheet) -> Unit,
) {
    val rendered = remember { mutableStateListOf<Rendered>() }
    LaunchedEffect(sheets.map { it.key }) {
        val keys = sheets.map { it.key }.toSet()
        rendered.forEach { r -> if (r.sheet.key !in keys) r.state.targetState = false }
        for (s in sheets) {
            val existing = rendered.firstOrNull { it.sheet.key == s.key }
            if (existing == null) {
                rendered.add(Rendered(s, MutableTransitionState(false).apply { targetState = true }))
            } else if (!existing.state.targetState) {
                existing.state.targetState = true
            }
        }
    }

    val anyVisible = sheets.isNotEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight
        AnimatedVisibility(visible = anyVisible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissTop)
                    .semantics { contentDescription = "Fermer le panneau" }
            )
        }
        for (r in rendered.toList()) {
            key(r.sheet.key) {
                LaunchedEffect(r.state.isIdle, r.state.currentState) {
                    if (r.state.isIdle && !r.state.currentState && !r.state.targetState) rendered.remove(r)
                }
                AnimatedVisibility(
                    visibleState = r.state,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = if (reduceMotion) fadeIn(tween(90)) else slideInVertically(spring(dampingRatio = 0.74f, stiffness = 420f)) { it } + fadeIn(tween(120)),
                    exit = if (reduceMotion) fadeOut(tween(90)) else slideOutVertically(tween(190)) { it } + fadeOut(tween(190)),
                ) {
                    val isTop = sheets.lastOrNull()?.key == r.sheet.key
                    SheetPanel(maxHeight = maxH * 0.92f, onDismiss = { if (isTop) onDismissTop() }) {
                        content(r.sheet)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetPanel(
    maxHeight: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val thresholdPx = with(LocalDensity.current) { 110.dp.toPx() }
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    Column(
        Modifier
            .statusBarsPadding()
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .heightIn(max = maxHeight)
            .glass(shape, Mn.Violet, 1.1f)
            // Absorbe les touches pour ne pas fermer le panneau en touchant son contenu.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        if (drag.value > thresholdPx || velocity > 1800f) {
                            onDismiss()
                            drag.snapTo(0f)
                        } else {
                            drag.animateTo(0f, spring(dampingRatio = 0.5f))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Mn.TextFaint)
            )
        }
        content()
        Box(Modifier.size(8.dp))
    }
}
