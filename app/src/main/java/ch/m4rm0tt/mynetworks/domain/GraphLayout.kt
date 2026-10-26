package ch.m4rm0tt.mynetworks.domain

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class NodeType { SELF, ENTITY, GROUP }
enum class EdgeKind { SELF_GROUP, GROUP_MEMBER, SELF_MEMBER, SECONDARY, FAVORITE }

class GraphNode(
    val id: String,
    val type: NodeType,
    val x: Float,
    val y: Float,
    val r: Float,
    val label: String,
    val initials: String,
    val favorite: Boolean = false,
    val photoId: String? = null,
    val kind: EntityKind? = null,
    val memberCount: Int = 0,
)

class GraphEdge(val from: Int, val to: Int, val kind: EdgeKind)

class LayoutItem(
    val id: String,
    val kind: EntityKind,
    val label: String,
    val initials: String,
    val favorite: Boolean,
    val photoId: String?,
    /** Clés des groupes possibles pour le critère courant (ids de valeurs communes ou d'entreprise). */
    val groupKeys: List<String>,
    val createdAt: Long,
)

class GraphLayoutResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val index: Map<String, Int>,
    /** Rayon englobant tout le graphe (coordonnées monde, centre en 0,0). */
    val extent: Float,
) {
    companion object {
        val EMPTY = GraphLayoutResult(emptyList(), emptyList(), emptyMap(), 0f)
    }
}

/**
 * Disposition déterministe et en O(n log n) :
 * - moi au centre ;
 * - les fiches sans groupe en spirale (phyllotaxie) autour de moi ;
 * - un groupe n'existe que si au moins 2 fiches partagent la valeur ;
 * - les groupes sont posés sur un anneau, triés par libellé, chacun avec ses membres en spirale.
 * Une fiche appartenant à plusieurs groupes n'est dessinée qu'une fois (dans son groupe principal,
 * le plus grand) et reliée par un lien secondaire discret à ses autres groupes.
 * Aucune relation n'est créée entre fiches : les liens vont toujours vers un groupe ou vers moi.
 */
object GraphLayout {
    const val SELF_R = 64f
    const val NODE_R = 26f
    const val GROUP_R = 44f
    private const val SPIRAL_C = 42f
    private const val GROUP_GAP = 90f
    private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()

    const val SELF_ID = "self"
    fun groupNodeId(key: String) = "g:$key"

    fun compute(
        selfLabel: String,
        selfInitials: String,
        selfPhotoId: String?,
        items: List<LayoutItem>,
        groupLabels: Map<String, String>,
    ): GraphLayoutResult {
        val nodes = ArrayList<GraphNode>(items.size + 64)
        val edges = ArrayList<GraphEdge>(items.size * 2 + 64)
        val index = HashMap<String, Int>(items.size * 2 + 64)

        fun add(n: GraphNode): Int {
            index[n.id] = nodes.size
            nodes.add(n)
            return nodes.size - 1
        }

        val selfIdx = add(GraphNode(SELF_ID, NodeType.SELF, 0f, 0f, SELF_R, selfLabel, selfInitials, photoId = selfPhotoId, kind = EntityKind.SELF))

        // 1. Groupes formés (≥ 2 membres).
        val counts = HashMap<String, Int>()
        for (it in items) for (k in it.groupKeys.distinct()) counts[k] = (counts[k] ?: 0) + 1
        val formed = counts.filter { it.value >= 2 }.keys
        val sortedGroups = formed.sortedWith(compareBy<String>({ TextNorm.fold(groupLabels[it] ?: "") }, { it }))
        val groupOrder = sortedGroups.withIndex().associate { it.value to it.index }

        // 2. Groupe principal de chaque fiche : le plus grand, puis l'ordre alphabétique.
        val members = HashMap<String, MutableList<LayoutItem>>()
        val ungrouped = ArrayList<LayoutItem>()
        val primaryOf = HashMap<String, String>()
        for (it in items) {
            val keys = it.groupKeys.distinct().filter { k -> k in formed }
            if (keys.isEmpty()) {
                ungrouped.add(it); continue
            }
            val primary = keys.minWith(compareBy<String>({ -(counts[it] ?: 0) }, { groupOrder[it] ?: 0 }))
            primaryOf[it.id] = primary
            members.getOrPut(primary) { ArrayList() }.add(it)
        }
        val stableOrder = compareBy<LayoutItem>({ it.createdAt }, { it.id })

        // 3. Fiches sans groupe : spirale autour de moi.
        ungrouped.sortWith(stableOrder)
        val selfClear = SELF_R + NODE_R + 34f
        val i0Self = ceil((selfClear / SPIRAL_C).let { it * it }).toInt()
        for ((i, it) in ungrouped.withIndex()) {
            val (x, y) = spiral(0f, 0f, i + i0Self, 0f)
            val idx = add(entityNode(it, x, y))
            edges.add(GraphEdge(selfIdx, idx, EdgeKind.SELF_MEMBER))
        }
        val innerRadius = if (ungrouped.isEmpty()) SELF_R + 40f
        else SPIRAL_C * sqrt((ungrouped.size + i0Self).toFloat()) + NODE_R

        // 4. Groupes sur un anneau.
        val groupClear = GROUP_R + NODE_R + 18f
        val i0Group = ceil((groupClear / SPIRAL_C).let { it * it }).toInt()
        val groupRadius = sortedGroups.associateWith { key ->
            val m = members[key]?.size ?: 0
            if (m == 0) GROUP_R + 10f else SPIRAL_C * sqrt((m + i0Group).toFloat()) + NODE_R
        }
        if (sortedGroups.isNotEmpty()) {
            val maxR = groupRadius.values.max()
            val arcSum = sortedGroups.sumOf { (2 * groupRadius.getValue(it) + GROUP_GAP).toDouble() }.toFloat()
            // arcSum / 4 ≥ arcSum / 2π × π/2 : marge qui garantit corde ≥ arc requis.
            val ring = if (sortedGroups.size == 1) innerRadius + maxR + GROUP_GAP
            else max(innerRadius + maxR + GROUP_GAP, arcSum / 4f)
            val slack = ((2 * PI * ring).toFloat() - arcSum).coerceAtLeast(0f) / sortedGroups.size
            var cursor = 0f
            val start = (-PI / 2).toFloat()
            for (key in sortedGroups) {
                val rg = groupRadius.getValue(key)
                val span = 2 * rg + GROUP_GAP + slack
                val angle = start + (cursor + span / 2f) / ring
                cursor += span
                val gx = ring * cos(angle)
                val gy = ring * sin(angle)
                val list = members[key].orEmpty().sortedWith(compareBy<LayoutItem>({ if (it.id == key) 0 else 1 }).then(stableOrder))
                val label = groupLabels[key] ?: "?"
                val gIdx = add(
                    GraphNode(
                        groupNodeId(key), NodeType.GROUP, gx, gy, GROUP_R, label, TextNorm.initials(label),
                        memberCount = counts[key] ?: 0,
                    )
                )
                edges.add(GraphEdge(selfIdx, gIdx, EdgeKind.SELF_GROUP))
                for ((i, it) in list.withIndex()) {
                    val (x, y) = spiral(gx, gy, i + i0Group, angle)
                    val idx = add(entityNode(it, x, y))
                    edges.add(GraphEdge(gIdx, idx, EdgeKind.GROUP_MEMBER))
                }
            }
        }

        // 5. Liens secondaires (autres groupes) et liens « légendaires » des favoris.
        for (it in items) {
            val idx = index[it.id] ?: continue
            val primary = primaryOf[it.id]
            if (primary != null) {
                for (k in it.groupKeys.distinct()) {
                    if (k == primary || k !in formed) continue
                    val g = index[groupNodeId(k)] ?: continue
                    edges.add(GraphEdge(idx, g, EdgeKind.SECONDARY))
                }
            }
            if (it.favorite) edges.add(GraphEdge(selfIdx, idx, EdgeKind.FAVORITE))
        }

        var extent = SELF_R
        for (n in nodes) extent = max(extent, sqrt(n.x * n.x + n.y * n.y) + n.r)
        return GraphLayoutResult(nodes, edges, index, extent)
    }

    private fun entityNode(it: LayoutItem, x: Float, y: Float) = GraphNode(
        it.id, NodeType.ENTITY, x, y, NODE_R, it.label, it.initials,
        favorite = it.favorite, photoId = it.photoId, kind = it.kind,
    )

    private fun spiral(cx: Float, cy: Float, i: Int, rotation: Float): Pair<Float, Float> {
        val r = SPIRAL_C * sqrt(i.toFloat())
        val a = i * GOLDEN_ANGLE + rotation
        return Pair(cx + r * cos(a), cy + r * sin(a))
    }
}
