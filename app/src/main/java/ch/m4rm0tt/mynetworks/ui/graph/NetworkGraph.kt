package ch.m4rm0tt.mynetworks.ui.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.domain.EdgeKind
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GraphLayout
import ch.m4rm0tt.mynetworks.domain.GraphLayoutResult
import ch.m4rm0tt.mynetworks.domain.GraphNode
import ch.m4rm0tt.mynetworks.domain.NodeType
import ch.m4rm0tt.mynetworks.ui.CameraState
import ch.m4rm0tt.mynetworks.ui.FocusRequest
import ch.m4rm0tt.mynetworks.ui.components.starPath
import ch.m4rm0tt.mynetworks.ui.theme.Mn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MIN_SCALE = 0.03f
private const val MAX_SCALE = 4f

/**
 * Carte du réseau dessinée sur un seul Canvas (pas d'arbre de vues par nœud) :
 * - coordonnées monde fournies par GraphLayout (calculées hors du thread UI) ;
 * - culling hors écran, niveaux de détail selon le zoom, miniatures chargées à la demande ;
 * - transitions élastiques entre deux dispositions, respiration discrète au repos.
 */
@Composable
fun NetworkGraph(
    layout: GraphLayoutResult,
    camera: CameraState,
    focus: FocusRequest?,
    selectedId: String?,
    photos: PhotoStore?,
    idleMotion: Boolean,
    reduceMotion: Boolean,
    bottomInsetFraction: Float,
    onTapNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dens = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val scope = rememberCoroutineScope()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // --- Transition entre dispositions ---
    val progress = remember { Animatable(1f) }
    var fromPos by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var shown by remember { mutableStateOf(layout) }
    LaunchedEffect(layout) {
        if (layout === shown) return@LaunchedEffect
        val previous = shown
        val p = progress.value
        val current = HashMap<String, Offset>(previous.nodes.size)
        for (n in previous.nodes) {
            val f = fromPos[n.id]
            current[n.id] = if (f != null && p < 1f) lerp(f, Offset(n.x, n.y), p) else Offset(n.x, n.y)
        }
        // Les nouveaux nœuds jaillissent de leur parent (groupe ou moi).
        val parent = HashMap<Int, Int>()
        for (e in layout.edges) if (e.kind == EdgeKind.GROUP_MEMBER || e.kind == EdgeKind.SELF_MEMBER || e.kind == EdgeKind.SELF_GROUP) parent[e.to] = e.from
        val from = HashMap<String, Offset>(layout.nodes.size)
        for ((i, n) in layout.nodes.withIndex()) {
            from[n.id] = current[n.id] ?: parent[i]?.let { pi ->
                val pn = layout.nodes[pi]
                current[pn.id] ?: Offset(pn.x, pn.y)
            } ?: Offset(n.x, n.y)
        }
        fromPos = from
        shown = layout
        if (reduceMotion || previous.nodes.isEmpty()) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessVeryLow))
        }
    }

    // --- Respiration au repos (uniquement au premier plan) ---
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(idleMotion, reduceMotion, lifecycle) {
        if (!idleMotion || reduceMotion) { time = 0f; return@LaunchedEffect }
        lifecycle.currentStateFlow.collectLatest { state ->
            if (state.isAtLeast(Lifecycle.State.RESUMED)) {
                val start = System.nanoTime()
                while (true) {
                    time = (System.nanoTime() - start) / 1e9f
                    delay(33)
                }
            }
        }
    }

    // --- Recentrage demandé (recherche, ajout) ---
    var pulseStart by remember { mutableLongStateOf(0L) }
    var pulseId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focus, viewSize, shown) {
        val f = focus ?: return@LaunchedEffect
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val idx = shown.index[f.nodeId] ?: return@LaunchedEffect
        if (pulseId == f.nodeId && System.currentTimeMillis() - pulseStart < 300) return@LaunchedEffect
        val n = shown.nodes[idx]
        camera.userMoved = true
        pulseId = f.nodeId
        pulseStart = System.currentTimeMillis()
        val targetScale = camera.scale.coerceAtLeast(1.1f)
        val targetX = viewSize.width / 2f - n.x * targetScale
        val targetY = viewSize.height * (1f - bottomInsetFraction) / 2f - n.y * targetScale
        val s0 = camera.scale; val x0 = camera.offsetX; val y0 = camera.offsetY
        val spec = if (reduceMotion) tween<Float>(0) else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
        animate(0f, 1f, animationSpec = spec) { v, _ ->
            camera.scale = s0 + (targetScale - s0) * v
            camera.offsetX = x0 + (targetX - x0) * v
            camera.offsetY = y0 + (targetY - y0) * v
        }
    }

    // --- Cadrage : tout le réseau tient à l'écran tant que l'utilisateur n'a pas bougé la vue ---
    LaunchedEffect(viewSize, shown, camera.initialized) {
        if (viewSize == IntSize.Zero || shown.nodes.isEmpty()) return@LaunchedEffect
        if (camera.initialized && camera.userMoved) return@LaunchedEffect
        val w = viewSize.width.toFloat(); val h = viewSize.height.toFloat()
        val extent = max(shown.extent, 260f)
        val ts = (min(w, h) * 0.45f / extent).coerceIn(MIN_SCALE, 1.4f)
        val tx = w / 2f
        val ty = h / 2f
        if (!camera.initialized || reduceMotion) {
            camera.scale = ts; camera.offsetX = tx; camera.offsetY = ty
            camera.initialized = true
        } else {
            val s0 = camera.scale; val x0 = camera.offsetX; val y0 = camera.offsetY
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) { v, _ ->
                if (camera.userMoved) return@animate
                camera.scale = s0 + (ts - s0) * v
                camera.offsetX = x0 + (tx - x0) * v
                camera.offsetY = y0 + (ty - y0) * v
            }
        }
    }

    // --- Miniatures visibles, chargées hors du thread UI ---
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(photos, viewSize) {
        val store = photos ?: return@LaunchedEffect
        snapshotFlow {
            val s = camera.scale
            if (s < PHOTO_MIN_SCALE || viewSize == IntSize.Zero) emptyList()
            else visibleNodes(shown, camera, viewSize, 0f).mapNotNull { shown.nodes[it].photoId }.take(160)
        }.distinctUntilChanged().collect { ids ->
            for (id in ids) {
                if (thumbs.containsKey(id)) continue
                store.thumb(id)?.let { thumbs[id] = it }
            }
            if (thumbs.size > 400) {
                val keep = ids.toSet()
                thumbs.keys.filter { it !in keep }.take(thumbs.size - 300).forEach { thumbs.remove(it) }
            }
        }
    }

    val star = remember { starPath(0f, 0f, 1f) }
    val clip = remember { Path() }
    val curve = remember { Path() }
    val labelStyle = TextStyle(color = Mn.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val groupStyle = TextStyle(color = Mn.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val dimStyle = TextStyle(color = Mn.TextDim, fontSize = 11.sp)
    val minTouchPx = with(dens) { 24.dp.toPx() }

    Canvas(
        modifier
            .onSizeChanged { viewSize = it }
            .semantics {
                val people = shown.nodes.count { it.type == NodeType.ENTITY }
                val groups = shown.nodes.count { it.type == NodeType.GROUP }
                contentDescription = "Carte du réseau : $people fiches, $groups groupes. Utilise Recherche pour ouvrir une fiche."
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.userMoved = true
                    val old = camera.scale
                    val next = (old * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val k = next / old
                    camera.offsetX = centroid.x - (centroid.x - camera.offsetX) * k + pan.x
                    camera.offsetY = centroid.y - (centroid.y - camera.offsetY) * k + pan.y
                    camera.scale = next
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val hit = hitTest(shown, camera, pos, minTouchPx)
                        if (hit != null) {
                            val n = shown.nodes[hit]
                            if (n.type == NodeType.GROUP) {
                                // Toucher un groupe zoome dessus.
                                camera.userMoved = true
                                scope.launch {
                                    val s0 = camera.scale; val x0 = camera.offsetX; val y0 = camera.offsetY
                                    val ts = max(s0, 0.9f).coerceAtMost(MAX_SCALE)
                                    val tx = viewSize.width / 2f - n.x * ts
                                    val ty = viewSize.height / 2f - n.y * ts
                                    val spec = if (reduceMotion) tween<Float>(0) else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                                    animate(0f, 1f, animationSpec = spec) { v, _ ->
                                        camera.scale = s0 + (ts - s0) * v
                                        camera.offsetX = x0 + (tx - x0) * v
                                        camera.offsetY = y0 + (ty - y0) * v
                                    }
                                }
                            } else {
                                onTapNode(n.id)
                            }
                        }
                    },
                    onDoubleTap = { pos ->
                        if (hitTest(shown, camera, pos, minTouchPx) == null) {
                            // Double-toucher le vide : revoir tout le réseau.
                            camera.userMoved = false
                            camera.initialized = false
                            return@detectTapGestures
                        }
                        camera.userMoved = true
                        val old = camera.scale
                        val next = (old * 1.8f).coerceAtMost(MAX_SCALE)
                        val k = next / old
                        camera.offsetX = pos.x - (pos.x - camera.offsetX) * k
                        camera.offsetY = pos.y - (pos.y - camera.offsetY) * k
                        camera.scale = next
                    },
                )
            }
    ) {
        val s = camera.scale
        val ox = camera.offsetX
        val oy = camera.offsetY
        val p = progress.value
        val t = time
        val g = shown
        val from = fromPos
        if (g.nodes.isEmpty()) return@Canvas

        // Positions écran (interpolées pendant une transition).
        val n = g.nodes.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val animating = p < 0.999f
        for (i in 0 until n) {
            val node = g.nodes[i]
            var wx = node.x
            var wy = node.y
            if (animating) {
                val f = from[node.id]
                if (f != null) { wx = f.x + (node.x - f.x) * p; wy = f.y + (node.y - f.y) * p }
            }
            if (t > 0f && node.type == NodeType.ENTITY) {
                val ph = (node.id.hashCode() and 0xffff) / 65535f * 6.283f
                wx += sin(t * 0.7f + ph) * 2.2f
                wy += sin(t * 0.55f + ph * 1.7f) * 2.2f
            }
            xs[i] = wx * s + ox
            ys[i] = wy * s + oy
        }
        val w = size.width
        val h = size.height
        val margin = 80f
        fun onScreen(i: Int): Boolean {
            val r = g.nodes[i].r * s + margin
            return xs[i] > -r && xs[i] < w + r && ys[i] > -r && ys[i] < h + r
        }
        fun segmentVisible(a: Int, b: Int): Boolean {
            val minX = min(xs[a], xs[b]); val maxX = max(xs[a], xs[b])
            val minY = min(ys[a], ys[b]); val maxY = max(ys[a], ys[b])
            return maxX > -margin && minX < w + margin && maxY > -margin && minY < h + margin
        }

        val showMembers = s >= AGGREGATE_SCALE
        fun hidden(i: Int): Boolean = !showMembers && g.nodes[i].grouped
        // Distance écran approximative entre deux voisins : les noms n'apparaissent que s'ils ont la place.
        val spacingPx = GraphLayout.SPIRAL_C * 1.55f * s
        val showNames = spacingPx >= 64f * dens.density
        val nameMaxPx = (spacingPx * 0.95f).toInt()

        // 1. Liens.
        for (e in g.edges) {
            val a = e.from; val b = e.to
            val nb = g.nodes[b]
            if (hidden(a) || hidden(b)) continue
            if (nb.type == NodeType.ENTITY && !showMembers && e.kind != EdgeKind.FAVORITE && e.kind != EdgeKind.SELF_MEMBER) continue
            if (!segmentVisible(a, b)) continue
            when (e.kind) {
                EdgeKind.SELF_GROUP -> drawSoftCurve(curve, xs[a], ys[a], xs[b], ys[b], Mn.VioletSoft.copy(alpha = 0.45f), max(1.5f, 3f * s), 0.18f)
                EdgeKind.GROUP_MEMBER -> drawLine(Mn.Violet.copy(alpha = 0.22f), Offset(xs[a], ys[a]), Offset(xs[b], ys[b]), max(1f, 1.6f * s))
                EdgeKind.SELF_MEMBER -> drawLine(Mn.Violet.copy(alpha = 0.18f), Offset(xs[a], ys[a]), Offset(xs[b], ys[b]), max(1f, 1.4f * s))
                EdgeKind.SECONDARY -> if (s >= SECONDARY_SCALE) drawLine(Mn.TextFaint.copy(alpha = 0.22f), Offset(xs[a], ys[a]), Offset(xs[b], ys[b]), max(0.8f, 1f * s))
                EdgeKind.FAVORITE -> {
                    // Lien « légendaire » : halo + trait jaune.
                    drawSoftCurve(curve, xs[a], ys[a], xs[b], ys[b], Mn.Legendary.copy(alpha = 0.08f), max(3f, 8f * s), -0.12f)
                    drawSoftCurve(curve, xs[a], ys[a], xs[b], ys[b], Mn.Legendary.copy(alpha = 0.6f), max(1.2f, 2f * s), -0.12f)
                }
            }
        }

        // 2. Nœuds.
        for (i in 0 until n) {
            val node = g.nodes[i]
            if (!onScreen(i)) continue
            val r = node.r * s
            when (node.type) {
                NodeType.SELF -> drawSelf(node, xs[i], ys[i], r, thumbs, clip)
                NodeType.GROUP -> drawGroup(node, xs[i], ys[i], r)
                NodeType.ENTITY -> {
                    if (hidden(i)) continue
                    if (r < 7f) {
                        // Niveau de détail réduit : un seul disque, sans reflets ni texte.
                        drawCircle(if (node.favorite) Mn.Legendary else Mn.VioletSoft.copy(alpha = 0.85f), max(r, 1.2f), Offset(xs[i], ys[i]))
                        continue
                    }
                    drawEntity(node, xs[i], ys[i], r, if (s >= PHOTO_MIN_SCALE) thumbs else null, clip, star)
                }
            }
            if (node.id == selectedId || node.id == pulseId) {
                val age = (System.currentTimeMillis() - pulseStart) / 1000f
                val pulse = if (node.id == pulseId && age < 2.5f) (1f + 0.25f * sin(age * 9f) * (1f - age / 2.5f)) else 1f
                drawCircle(Mn.Violet, r * 1.28f * pulse, Offset(xs[i], ys[i]), style = Stroke(width = 2.5f))
            }
        }

        // 3. Textes (au-dessus des nœuds).
        for (i in 0 until n) {
            val node = g.nodes[i]
            if (!onScreen(i)) continue
            val r = node.r * s
            when (node.type) {
                NodeType.SELF -> {
                    if (thumbs[node.photoId ?: ""] == null) drawCentered(measurer, node.initials, groupStyle.copy(fontSize = (r * 0.42f / (dens.density * dens.fontScale)).coerceIn(10f, 40f).sp), xs[i], ys[i])
                    drawLabel(measurer, node.label, groupStyle, xs[i], ys[i] + r + 6f, (260 * dens.density).toInt())
                }
                NodeType.GROUP -> {
                    if (r >= 4f) {
                        drawLabel(measurer, node.label, groupStyle, xs[i], ys[i] + r + 6f, (220 * dens.density).toInt())
                        drawLabel(measurer, "${node.memberCount}", dimStyle, xs[i], ys[i] - 8f * dens.density, (80 * dens.density).toInt())
                    }
                }
                NodeType.ENTITY -> {
                    if (hidden(i)) continue
                    val hasPhoto = s >= PHOTO_MIN_SCALE && node.photoId != null && thumbs[node.photoId] != null
                    if (r >= 11f && !hasPhoto) {
                        drawCentered(measurer, node.initials, labelStyle.copy(fontSize = (r * 0.62f / (dens.density * dens.fontScale)).coerceIn(8f, 30f).sp, fontWeight = FontWeight.SemiBold), xs[i], ys[i])
                    }
                    if (showNames || node.id == selectedId) {
                        drawLabel(measurer, node.label, labelStyle, xs[i], ys[i] + r + 4f, if (node.id == selectedId) (220 * dens.density).toInt() else nameMaxPx)
                    }
                }
            }
        }
    }
}

private const val AGGREGATE_SCALE = 0.16f
private const val SECONDARY_SCALE = 0.35f
private const val PHOTO_MIN_SCALE = 0.45f


private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Indices des nœuds visibles à l'écran (pour charger les miniatures). */
private fun visibleNodes(g: GraphLayoutResult, cam: CameraState, size: IntSize, margin: Float): List<Int> {
    val out = ArrayList<Int>()
    val s = cam.scale
    for ((i, n) in g.nodes.withIndex()) {
        if (n.photoId == null) continue
        val x = n.x * s + cam.offsetX
        val y = n.y * s + cam.offsetY
        val r = n.r * s + margin
        if (x > -r && x < size.width + r && y > -r && y < size.height + r) out.add(i)
    }
    return out
}

private fun hitTest(g: GraphLayoutResult, cam: CameraState, pos: Offset, minTouchPx: Float): Int? {
    val s = cam.scale
    var best: Int? = null
    var bestScore = Float.MAX_VALUE
    val showMembers = s >= AGGREGATE_SCALE
    for ((i, n) in g.nodes.withIndex()) {
        if (!showMembers && n.grouped) continue
        val x = n.x * s + cam.offsetX
        val y = n.y * s + cam.offsetY
        val r = max(n.r * s, minTouchPx)
        val d = hypot(pos.x - x, pos.y - y)
        if (d <= r) {
            // Priorité aux fiches sur les groupes, puis au plus proche.
            val score = d / r + if (n.type == NodeType.GROUP) 1f else 0f
            if (score < bestScore) { bestScore = score; best = i }
        }
    }
    return best
}

private fun DrawScope.drawSoftCurve(path: Path, x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float, bend: Float) {
    val mx = (x1 + x2) / 2f; val my = (y1 + y2) / 2f
    val dx = x2 - x1; val dy = y2 - y1
    path.reset()
    path.moveTo(x1, y1)
    path.quadraticBezierTo(mx - dy * bend, my + dx * bend, x2, y2)
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawGlassDisc(cx: Float, cy: Float, r: Float, base: Color, tint: Color, rounded: Boolean) {
    val c = Offset(cx, cy)
    if (rounded) {
        val tl = Offset(cx - r, cy - r)
        val sz = Size(2 * r, 2 * r)
        val cr = CornerRadius(r * 0.45f)
        drawRoundRect(base, tl, sz, cr)
        drawRoundRect(tint, Offset(cx - r * 0.86f, cy - r * 0.92f), Size(r * 1.5f, r * 1.3f), CornerRadius(r * 0.4f))
        drawOval(Color.White.copy(alpha = 0.16f), Offset(cx - r * 0.62f, cy - r * 0.78f), Size(r * 0.7f, r * 0.34f))
        drawRoundRect(Mn.Rim, tl, sz, cr, style = Stroke(width = max(1f, r * 0.05f)))
    } else {
        drawCircle(base, r, c)
        drawCircle(tint, r * 0.78f, Offset(cx - r * 0.14f, cy - r * 0.16f))
        drawOval(Color.White.copy(alpha = 0.16f), Offset(cx - r * 0.58f, cy - r * 0.74f), Size(r * 0.66f, r * 0.32f))
        drawCircle(Mn.Rim, r, c, style = Stroke(width = max(1f, r * 0.05f)))
    }
}

private fun DrawScope.drawEntity(node: GraphNode, cx: Float, cy: Float, r: Float, thumbs: Map<String, ImageBitmap>?, clip: Path, star: Path) {
    val company = node.kind == EntityKind.COMPANY
    if (node.favorite) drawCircle(Mn.Legendary.copy(alpha = 0.12f), r * 1.45f, Offset(cx, cy))
    drawGlassDisc(cx, cy, r, Mn.VioletInk.copy(alpha = 0.95f), Mn.Violet.copy(alpha = if (company) 0.10f else 0.16f), company)
    val img = node.photoId?.let { thumbs?.get(it) }
    if (img != null) drawPhoto(img, cx, cy, r * 0.9f, clip, company)
    if (node.favorite && r >= 8f) {
        val k = r * 0.32f
        val sx = cx + r * 0.72f; val sy = cy - r * 0.72f
        drawCircle(Mn.Black, k * 1.25f, Offset(sx, sy))
        withTransform({ translate(sx, sy); scale(k, k, Offset.Zero) }) {
            drawPath(star, Mn.Legendary)
        }
    } else if (node.favorite) {
        drawCircle(Mn.Legendary, max(1.5f, r * 0.3f), Offset(cx + r * 0.7f, cy - r * 0.7f))
    }
}

private fun DrawScope.drawSelf(node: GraphNode, cx: Float, cy: Float, r: Float, thumbs: Map<String, ImageBitmap>, clip: Path) {
    val c = Offset(cx, cy)
    drawCircle(
        Brush.radialGradient(listOf(Mn.Violet.copy(alpha = 0.28f), Color.Transparent), center = c, radius = r * 2.1f),
        r * 2.1f, c,
    )
    drawGlassDisc(cx, cy, r, Mn.VioletDeep, Mn.Violet.copy(alpha = 0.32f), false)
    val img = node.photoId?.let { thumbs[it] }
    if (img != null) drawPhoto(img, cx, cy, r * 0.9f, clip, false)
    drawCircle(Mn.Violet.copy(alpha = 0.6f), r, c, style = Stroke(width = max(1.5f, r * 0.05f)))
}

private fun DrawScope.drawGroup(node: GraphNode, cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    drawCircle(Mn.Violet.copy(alpha = 0.08f), r * 1.5f, c)
    drawGlassDisc(cx, cy, r, Mn.Surface3, Mn.VioletSoft.copy(alpha = 0.30f), false)
    drawCircle(Mn.Violet.copy(alpha = 0.45f), r * 1.12f, c, style = Stroke(width = max(1f, r * 0.04f)))
}

private fun DrawScope.drawPhoto(img: ImageBitmap, cx: Float, cy: Float, r: Float, clip: Path, rounded: Boolean) {
    clip.reset()
    val rect = Rect(cx - r, cy - r, cx + r, cy + r)
    if (rounded) clip.addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(r * 0.42f))) else clip.addOval(rect)
    clipPath(clip) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((cx - r).toInt(), (cy - r).toInt()),
            dstSize = IntSize((2 * r).toInt().coerceAtLeast(1), (2 * r).toInt().coerceAtLeast(1)),
        )
    }
}

private fun DrawScope.drawCentered(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, cx: Float, cy: Float) {
    val layout = measurer.measure(text, style, maxLines = 1)
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}

private fun DrawScope.drawLabel(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, cx: Float, top: Float, maxWidthPx: Int) {
    val maxW = maxWidthPx.coerceAtLeast(1)
    var layout = measurer.measure(
        text, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = maxW),
    )
    // Si le nom complet ne tient pas, on garde le premier mot (souvent le prénom).
    if (layout.hasVisualOverflow && text.contains(' ')) {
        layout = measurer.measure(
            text.substringBefore(' '), style, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = maxW),
        )
    }
    val x = cx - layout.size.width / 2f
    if (x > size.width || x + layout.size.width < 0 || top > size.height || top + layout.size.height < 0) return
    // Pastille sombre derrière le texte pour rester lisible sur les liens.
    drawRoundRect(
        Mn.Black.copy(alpha = 0.55f),
        Offset(x - 6f, top - 2f),
        Size(layout.size.width + 12f, layout.size.height + 4f),
        CornerRadius(layout.size.height / 2f),
    )
    drawText(layout, topLeft = Offset(x, top))
}
