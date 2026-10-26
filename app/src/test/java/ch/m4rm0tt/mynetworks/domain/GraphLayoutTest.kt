package ch.m4rm0tt.mynetworks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GraphLayoutTest {

    private fun item(id: String, keys: List<String> = emptyList(), fav: Boolean = false, t: Long = id.hashCode().toLong()) =
        LayoutItem(id, EntityKind.PERSON, id, TextNorm.initials(id), fav, null, keys, t)

    private fun layout(items: List<LayoutItem>, labels: Map<String, String> = emptyMap()) =
        GraphLayout.compute("Moi", "M", null, items, labels)

    @Test
    fun emptyNetworkHasOnlyMe() {
        val r = layout(emptyList())
        assertEquals(1, r.nodes.size)
        assertEquals(NodeType.SELF, r.nodes[0].type)
        assertTrue(r.edges.isEmpty())
    }

    @Test
    fun groupNeedsAtLeastTwoMembers() {
        val r = layout(
            listOf(item("a", listOf("k1")), item("b", listOf("k1")), item("c", listOf("k2"))),
            mapOf("k1" to "Vevey", "k2" to "Riviera"),
        )
        assertNotNull(r.index[GraphLayout.groupNodeId("k1")])
        assertNull(r.index[GraphLayout.groupNodeId("k2")])
        assertEquals(2, r.nodes[r.index.getValue(GraphLayout.groupNodeId("k1"))].memberCount)
    }

    @Test
    fun multiGroupEntityIsDrawnOnceWithSecondaryLink() {
        val items = listOf(
            item("a", listOf("x", "y")), item("b", listOf("x")), item("c", listOf("x")), item("d", listOf("y")),
        )
        val r = layout(items, mapOf("x" to "Design", "y" to "Musique"))
        assertEquals(1, r.nodes.count { it.id == "a" })
        val a = r.index.getValue("a")
        val gx = r.index.getValue(GraphLayout.groupNodeId("x"))
        val gy = r.index.getValue(GraphLayout.groupNodeId("y"))
        // Groupe principal = le plus grand (x : 3 membres).
        assertTrue(r.edges.any { it.from == gx && it.to == a && it.kind == EdgeKind.GROUP_MEMBER })
        assertTrue(r.edges.any { it.from == a && it.to == gy && it.kind == EdgeKind.SECONDARY })
    }

    @Test
    fun favoritesGetLegendaryLinkToMe() {
        val r = layout(listOf(item("a", fav = true), item("b")))
        val a = r.index.getValue("a")
        val b = r.index.getValue("b")
        assertTrue(r.edges.any { it.from == 0 && it.to == a && it.kind == EdgeKind.FAVORITE })
        assertFalse(r.edges.any { it.to == b && it.kind == EdgeKind.FAVORITE })
    }

    @Test
    fun noRelationIsCreatedBetweenEntities() {
        val items = (0 until 200).map { item("p$it", listOf("k${it % 7}", "s${it % 3}")) }
        val labels = (0 until 7).associate { "k$it" to "K$it" } + (0 until 3).associate { "s$it" to "S$it" }
        val r = layout(items, labels)
        for (e in r.edges) {
            val a = r.nodes[e.from].type
            val b = r.nodes[e.to].type
            assertFalse("lien direct entre deux fiches", a == NodeType.ENTITY && b == NodeType.ENTITY)
        }
        // Linéaire : au plus 1 lien principal + liens secondaires + favori par fiche.
        assertTrue(r.edges.size <= items.size * 3 + 10)
    }

    @Test
    fun layoutIsDeterministicAndStableWhenAddingSomeone() {
        val base = (0 until 50).map { item("p$it", listOf("k${it % 4}"), t = it.toLong()) }
        val labels = (0 until 4).associate { "k$it" to "Groupe $it" }
        val r1 = layout(base, labels)
        val r2 = layout(base.shuffled(), labels)
        for (n in r1.nodes) {
            val m = r2.nodes[r2.index.getValue(n.id)]
            assertEquals(n.x, m.x, 0.01f); assertEquals(n.y, m.y, 0.01f)
        }
        // Ajouter une fiche sans groupe ne déplace pas les fiches sans groupe existantes.
        val loose = (0 until 20).map { item("l$it", t = it.toLong()) }
        val r3 = layout(loose, labels)
        val r4 = layout(loose + item("new", t = 999), labels)
        for (n in r3.nodes) {
            val m = r4.nodes[r4.index.getValue(n.id)]
            assertEquals(n.x, m.x, 0.01f); assertEquals(n.y, m.y, 0.01f)
        }
    }

    @Test
    fun nodesDoNotOverlap() {
        for (size in listOf(10, 100, 1000)) {
            val items = (0 until size).map { item("p$it", if (it % 3 == 0) emptyList() else listOf("k${it % 11}"), t = it.toLong()) }
            val labels = (0 until 11).associate { "k$it" to "Valeur $it" }
            val r = layout(items, labels)
            val n = r.nodes
            // Grille spatiale pour rester rapide.
            val cell = 120f
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
            n.forEachIndexed { i, node -> grid.getOrPut(key((node.x / cell).toInt(), (node.y / cell).toInt())) { mutableListOf() }.add(i) }
            for ((i, a) in n.withIndex()) {
                val cx = (a.x / cell).toInt(); val cy = (a.y / cell).toInt()
                for (dx in -2..2) for (dy in -2..2) for (j in grid[key(cx + dx, cy + dy)].orEmpty()) {
                    if (j <= i) continue
                    val b = n[j]
                    val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
                    assertTrue("chevauchement ${a.id}/${b.id} d=$d (n=$size)", d >= (a.r + b.r) * 0.98f)
                }
            }
        }
    }

    @Test
    fun tenThousandEntitiesStayFast() {
        val items = (0 until 10_000).map { item("p$it", listOf("k${it % 40}", "s${it % 9}"), fav = it % 97 == 0, t = it.toLong()) }
        val labels = (0 until 40).associate { "k$it" to "K$it" } + (0 until 9).associate { "s$it" to "S$it" }
        layout(items, labels) // échauffement JIT
        val t0 = System.nanoTime()
        val r = layout(items, labels)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("GraphLayout 10 000 fiches : ${ms} ms, ${r.nodes.size} nœuds, ${r.edges.size} liens")
        assertEquals(10_000 + 1 + 49, r.nodes.size)
        assertTrue("trop lent : $ms ms", ms < 2_000)
    }
}
